package com.iota.iri.network.replicator;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.List;
import java.util.zip.CRC32;

import com.iota.iri.network.TCPNeighbor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iota.iri.network.Neighbor;
import com.iota.iri.conf.Configuration;
import com.iota.iri.hash.Curl;
import com.iota.iri.model.Hash;
import com.iota.iri.network.Node;
import com.iota.iri.controllers.TransactionViewModel;

class ReplicatorSourceProcessor implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ReplicatorSourceProcessor.class);

    private final Socket connection;

    private final static int TRANSACTION_PACKET_SIZE = Node.TRANSACTION_PACKET_SIZE;
    private final boolean shutdown = false;
    private final Node node;
    private final int maxPeers;
    private final boolean testnet;
    private final ReplicatorSinkPool replicatorSinkPool;

    private boolean existingNeighbor;
    
    private TCPNeighbor neighbor;

    public ReplicatorSourceProcessor(final ReplicatorSinkPool replicatorSinkPool,
                                     final Socket connection,
                                     final Node node,
                                     final int maxPeers,
                                     final boolean testnet) {
        this.connection = connection;
        this.node = node;
        this.maxPeers = maxPeers;
        this.testnet = testnet;
        this.replicatorSinkPool = replicatorSinkPool;
    }

    @Override
    public void run() {
        int count;
        byte[] data = new byte[2000];
        int offset = 0;
        //boolean isNew;
        boolean finallyClose = true;

        try {

            SocketAddress address = connection.getRemoteSocketAddress();
            InetSocketAddress inet_socket_address = (InetSocketAddress) address;

            existingNeighbor = false;
            List<Neighbor> neighbors = node.getNeighbors();
            neighbors.stream().filter(n -> n instanceof TCPNeighbor)
                    .map(n -> ((TCPNeighbor) n))
                    .forEach(n -> {
                        String hisAddress = inet_socket_address.getAddress().getHostAddress();
                        if (n.getHostAddress().equals(hisAddress)) {
                            existingNeighbor = true;
                            neighbor = n;
                        }
                    });
            
            if (!existingNeighbor) {
                int maxPeersAllowed = maxPeers;
                if (!testnet || Neighbor.getNumPeers() >= maxPeersAllowed) {
                    String hostAndPort = inet_socket_address.getHostName() + ":" + String.valueOf(inet_socket_address.getPort());
                    if (Node.rejectedAddresses.add(inet_socket_address.getHostName())) {
                        String sb = "***** NETWORK ALERT ***** Got connected from unknown neighbor tcp://"
                            + hostAndPort
                            + " (" + inet_socket_address.getAddress().getHostAddress() + ") - closing connection";                    
                        if (testnet && Neighbor.getNumPeers() >= maxPeersAllowed) {
                            sb = sb + (" (max-peers allowed is "+String.valueOf(maxPeersAllowed)+")");
                        }
                        log.info(sb);
                    }
                    connection.getInputStream().close();
                    connection.shutdownInput();
                    connection.shutdownOutput();
                    connection.close();
                    return;
                } else {
                    final TCPNeighbor fresh_neighbor = new TCPNeighbor(inet_socket_address, false);
                    node.getNeighbors().add(fresh_neighbor);
                    neighbor = fresh_neighbor;
                    Neighbor.incNumPeers();
                }
            }
            
            if ( neighbor.getSource() != null ) {
                log.info("Source {} already connected", inet_socket_address.getAddress().getHostAddress());
                finallyClose = false;
                return;
            }
            neighbor.setSource(connection);
            
            // Read neighbors tcp listener port number.
            InputStream stream = connection.getInputStream();
            offset = 0;
            while (((count = stream.read(data, offset, ReplicatorSinkPool.PORT_BYTES - offset)) != -1) && (offset < ReplicatorSinkPool.PORT_BYTES)) {
                offset += count;
            }
          
            if ( count == -1 || connection.isClosed() ) {
                log.error("Did not receive neighbors listener port");
                return;
            }
            
            byte [] pbytes = new byte [10];
            System.arraycopy(data, 0, pbytes, 0, ReplicatorSinkPool.PORT_BYTES);
            neighbor.setTcpPort((int)Long.parseLong(new String(pbytes)));
            
            if (neighbor.getSink() == null) {
                log.info("Creating sink for {}", neighbor.getHostAddress());
                replicatorSinkPool.createSink(neighbor);
            }           
            
            if (connection.isConnected()) {
                log.info("----- NETWORK INFO ----- Source {} is connected", inet_socket_address.getAddress().getHostAddress());
            }
            
            connection.setSoTimeout(0);  // infinite timeout - blocking read

            offset = 0;
            while (!shutdown && !neighbor.isStopped()) {

                while ( ((count = stream.read(data, offset, (TRANSACTION_PACKET_SIZE - offset + ReplicatorSinkProcessor.CRC32_BYTES))) != -1) 
                        && (offset < (TRANSACTION_PACKET_SIZE + ReplicatorSinkProcessor.CRC32_BYTES))) {
                    offset += count;
                }
              
                if ( count == -1 || connection.isClosed() ) {
                    break;
                }
                
                offset = 0;

                try {
                    CRC32 crc32 = new CRC32();
                    for (int i=0; i<TRANSACTION_PACKET_SIZE; i++) {
                        crc32.update(data[i]);
                    }
                    String crc32_string = Long.toHexString(crc32.getValue());
                    while (crc32_string.length() < ReplicatorSinkProcessor.CRC32_BYTES) {
                        crc32_string = "0"+crc32_string;
                    }
                    byte [] crc32_bytes = crc32_string.getBytes();
                    
                    boolean crcError = false;
                    for (int i=0; i<ReplicatorSinkProcessor.CRC32_BYTES; i++) {
                        if (crc32_bytes[i] != data[TRANSACTION_PACKET_SIZE + i]) {
                            crcError = true;
                            break;
                        }
                    }
                    if (!crcError) {
                        node.preProcessReceivedData(data, address, "tcp");
                    }
                }
                  catch (IllegalStateException e) {
                    log.error("Queue is full for neighbor IP {}",inet_socket_address.getAddress().getHostAddress());
                } catch (final RuntimeException e) {
                    log.error("Transaction processing runtime exception ",e);
                    neighbor.incInvalidTransactions();
                } catch (Exception e) {
                    log.info("Transaction processing exception " + e.getMessage());
                    log.error("Transaction processing exception ",e);
                }
            }
        } catch (IOException e) {
            log.error("***** NETWORK ALERT ***** TCP connection reset by neighbor {}, source closed, {}", neighbor.getHostAddress(), e.getMessage());
            replicatorSinkPool.shutdownSink(neighbor);
        } finally {
            if (neighbor != null) {
                if (finallyClose) {
                    replicatorSinkPool.shutdownSink(neighbor);
                    neighbor.setSource(null);
                    neighbor.setSink(null);
                    //if (!neighbor.isFlagged() ) {
                    //   Node.instance().getNeighbors().remove(neighbor);   
                    //}
                }                   
            }
        }
    }
}
