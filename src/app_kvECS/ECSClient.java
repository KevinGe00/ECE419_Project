package app_kvECS;

import app_kvServer.KVServerChild;
import app_kvServer.KVServer;
import ecs.ECSNode;
import ecs.IECSNode;
import logger.LogSetup;
import shared.messages.KVMessage;

import org.apache.commons.cli.*;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class ECSClient implements IECSClient {
    /**
     * Datatype containing a pair of response socket and thread.
     */
    class ChildObject {
        public Thread childThread;
        public Socket childSocket;
    }

    class RemovedNode {
        public Boolean success;
        public int port;
        public String storeDir;
        public List<BigInteger> range;
        public String successor;
    }

    public static class WLPackage {
        public boolean needsWL;
        public String valueSend;
    }

    private static Logger logger = Logger.getRootLogger();
    private String address;
    private int port;
    private boolean running = true;
    private ServerSocket serverSocket;
    private BufferedReader stdin;
    public HashMap<String, ChildObject> childObjects;
    public HashMap<String, String> successors;
    public HashMap<String, WLPackage> wlPackages;

    public HashMap<BigInteger, IECSNode> getHashRing() {
        return hashRing;
    }

    private HashMap<BigInteger, IECSNode> hashRing = new HashMap<>();
    //mapping of KVServers (defined by their IP:Port) and the associated range of hash value
    private HashMap<String, List<BigInteger>> metadata = new HashMap<>();
    private ArrayList<Thread> clients = new ArrayList<Thread>();
    public ECSClient(String address, int port) {
        this.address = address;
        this.port = port;
    }
    public HashMap<String, List<BigInteger>> getMetadata() {
        return metadata;
    }

    public boolean getRunning(){
        return running;
    }
    /* Port */
    public int getPort(){
        return port;
    }
    public void initializeServer() {
        logger.info("Initialize ECS server socket ...");
        // find IP for host
        InetAddress bindAddr;
        try {
            bindAddr = InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            logger.error("Error! IP address for host '" + address
                    + "' cannot be found.");
            return;
        }
        // create socket with IP and port
        try {
            serverSocket = new ServerSocket(port, 0, bindAddr);
            serverSocket.setReuseAddress(true);
            logger.info("ECS server host: " + serverSocket.getInetAddress().getHostName()
                    + " \tport: " + serverSocket.getLocalPort());
        } catch (IOException e) {
            logger.error("Error! Cannot open ECS server socket on host: '"
                    + address + "' \tport: " + port);
            closeServerSocket();
            return;
        }

        // initialize client object(thread + socket) arraylist
        childObjects = new HashMap<>();

        successors = new HashMap<>();

        wlPackages = new HashMap<>();
    }

    /*
     * close the listening socket of the server.
     * should not be directly called in run().
     */
    private void closeServerSocket() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
                serverSocket = null;
            } catch (Exception e) {
                logger.error("Unexpected Error! " +
                        "Unable to close socket on host: '" + address
                        + "' \tport: " + port, e);
            }
        }
    }
    @Override
    public boolean start() {
        // TODO
        return false;
    }

    @Override
    public boolean stop() {
        // TODO
        return false;
    }

    @Override
    public boolean shutdown() {
        // TODO
        return false;
    }

    @Override
    public IECSNode addNode(String cacheStrategy, int cacheSize) {
        // TODO
        return null;
    }

    @Override
    public Collection<IECSNode> addNodes(int count, String cacheStrategy, int cacheSize) {
        // TODO
        return null;
    }

    @Override
    public Collection<IECSNode> setupNodes(int count, String cacheStrategy, int cacheSize) {
        // TODO
        return null;
    }

    @Override
    public boolean awaitNodes(int count, int timeout) throws Exception {
        // TODO
        return false;
    }

    @Override
    public boolean removeNodes(Collection<String> nodeNames) {
        // TODO
        return false;
    }

    @Override
    public Map<String, IECSNode> getNodes() {
        // TODO
        return null;
    }

    @Override
    public IECSNode getNodeByKey(String Key) {
        // TODO
        return null;
    }

    /**
     * @param serverHost host name of server to be added as node
     * @param serverPort port of server to be added as node
     * @param storeDir path to storage directory of server
     * @return true on success, otherwise false
     */
    public boolean addNewNode(String serverHost, int serverPort, String storeDir) {
        try {
            String fullAddress =  serverHost + ":" + serverPort;
            BigInteger position = addNewServerNodeToHashRing(serverHost, serverPort, storeDir);
            updateMetadataWithNewNode(fullAddress, position);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @param serverHost host name of server removed
     * @param serverPort port of server removed
     * @return true and storage dir of removed node, and it's keyrange on success, otherwise false and null string otherwise
     */
    public RemovedNode removeNode(String serverHost, int serverPort) {
        RemovedNode rn =  new RemovedNode();

        try {
            String fullAddress =  serverHost + ":" + serverPort;
            String removedNodeStoreDir = removeServerNodeFromHashRing(serverHost, serverPort);
            List<BigInteger> removedNodeRange = updateMetadataAfterNodeRemoval(fullAddress);

            rn.success = true;
            rn.storeDir = removedNodeStoreDir;
            rn.range = removedNodeRange;
            rn.successor = successors.get(fullAddress);

            successors.remove(fullAddress);
            wlPackages.remove(fullAddress);

            return rn;
        } catch (Exception e) {
            rn.success = false;
            return rn;
        }
    }


    /**
     * @param fullAddress IP:port of newly added server
     * @param position the hash of fullAddress, used to determine the position of this node on the hashring
     */
    public void updateMetadataWithNewNode (String fullAddress, BigInteger position) {
        if (metadata.isEmpty()) {
            // add dummy node to handle wrap-around
            List<BigInteger> range1 = Arrays.asList(BigInteger.ZERO, position);
            metadata.put("dummy:1000", range1);

            // insert new node into metadata mapping
            List<BigInteger> range2 = Arrays.asList(position, BigInteger.ZERO);
            metadata.put(fullAddress, range2);

            successors.put(fullAddress, "dummy:1000");
            successors.put("dummy:1000", fullAddress);

            /* initialize WL package */
            WLPackage pck = new WLPackage();
            pck.needsWL = false;
            pck.valueSend = "";
            wlPackages.put("dummy:1000", pck);

            pck = new WLPackage();
            pck.needsWL = false;
            pck.valueSend = "";
            wlPackages.put(fullAddress, pck);


        } else {
            List<Map.Entry<String, List<BigInteger>>> sortedEntries = getSortedMetadata();

            BigInteger prevStart = BigInteger.ZERO;
            ECSNode prevNode = null;
            boolean inserted = false;
            // Iterate through the sorted map entries
            for (Map.Entry<String, List<BigInteger>> entry : sortedEntries) {
                String key = entry.getKey();
                BigInteger rangeStart = entry.getValue().get(0);
                int comparisonResult = rangeStart.compareTo(position);
                if (comparisonResult > 0) {
                    // start of key range for current entry is larger than new server's position
                    List<BigInteger> range = Arrays.asList(position, prevStart);
                    metadata.put(fullAddress, range);

                    // cut the range of the successor node, which is the current entry
                    List<BigInteger> successorRange = entry.getValue();
                    successorRange.set(1, position);
                    metadata.put(key, successorRange);

                    successors.put(fullAddress, key);
                    /* initialize WL package */
                    WLPackage pck = new WLPackage();
                    pck.needsWL = false;
                    pck.valueSend = "";
                    wlPackages.put(fullAddress, pck);

                    inserted = true;
                    break;
                }
                prevStart = rangeStart;
            }

            if (!inserted) {
                // new node is predecessor of dummy node at 0 since it is the one with the largest hash value
                List<BigInteger> range = Arrays.asList(position, prevStart);
                metadata.put(fullAddress, range);

                // TODO: once we connect servers to ecs, save servers (or at least their directory) to use here
//                moveFiles(oldServer, newServer, range)

                // cut the range of the successor node, which is the dummy node
                Map.Entry<String, List<BigInteger>> dummy = sortedEntries.get(0);
                List<BigInteger> successorRange = dummy.getValue();
                successorRange.set(1, position);
                metadata.put("dummy:1000", successorRange);
            };
        }
    }

    // hash string to MD5 bigint
    private BigInteger hash(String fullAddress) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(fullAddress.getBytes());
            byte[] digest = md.digest();
            return new BigInteger(1, digest);
        } catch (NoSuchAlgorithmException e) {
            logger.error(e);
            throw new RuntimeException(e);
        }
    }
    private BigInteger addNewServerNodeToHashRing(String serverHost, int serverPort, String storeDir) {
        String fullAddress =  serverHost + ":" + serverPort;
        BigInteger hashInt = hash(fullAddress);

        // Create new ECSNode
        String nodeName = "Server:" + fullAddress;
        ECSNode node = new ECSNode(nodeName, serverHost, serverPort, storeDir);

        hashRing.put(hashInt, node);
        return hashInt;
    }

    private String removeServerNodeFromHashRing(String serverHost, int serverPort) {
        String fullAddress =  serverHost + ":" + serverPort;
        BigInteger key = hash(fullAddress);

        if (!this.hashRing.containsKey(key)) {
            System.out.println("Error: Key '" + key + "' not found in hashmap");
            return "";
        } else {
            String removedNodeStoreDir = this.hashRing.get(key).getStoreDir();

            this.hashRing.remove(key);
            return removedNodeStoreDir;
        }
    };

    /**
     * @param fullAddress IP:port of removed server
     */
    public List<BigInteger> updateMetadataAfterNodeRemoval (String fullAddress) {
        if (metadata.containsKey(fullAddress)) {
            BigInteger nodeToRemoveRangeStart = metadata.get(fullAddress).get(0);
            BigInteger nodeToRemoveRangeEnd = metadata.get(fullAddress).get(1);

            List<Map.Entry<String, List<BigInteger>>> sortedEntries = getSortedMetadata();
            BigInteger prevStart = BigInteger.ZERO;
            for (Map.Entry<String, List<BigInteger>> entry : sortedEntries) {
                String key = entry.getKey();
                BigInteger rangeStart = entry.getValue().get(0);
                int comparisonResult = rangeStart.compareTo(nodeToRemoveRangeStart);
                if (comparisonResult > 0) {
                    List<BigInteger> removedNodeRange = metadata.get(fullAddress);
                    metadata.remove(fullAddress);

                    // extend the range of the successor node to cover removed node range
                    List<BigInteger> successorRange = entry.getValue();
                    successorRange.set(1, nodeToRemoveRangeEnd);
                    metadata.put(key, successorRange);
    //                moveFiles(successor, serverToRemove, Arrays.asList(nodeToRemoveRangeStart, nodeToRemoveRangeEnd))
                    return removedNodeRange;
                }
            }
        }
        return null;
    }

    // return sorted nodes in metadata map by the start of their key-range
    private List<Map.Entry<String, List<BigInteger>>> getSortedMetadata() {
        List<Map.Entry<String, List<BigInteger>>> sortedEntries = new ArrayList<>(metadata.entrySet());
        Collections.sort(sortedEntries, new Comparator<Map.Entry<String, List<BigInteger>>>() {
            @Override
            public int compare(Map.Entry<String, List<BigInteger>> e1, Map.Entry<String, List<BigInteger>> e2) {
                BigInteger first1 = e1.getValue().get(0);
                BigInteger first2 = e2.getValue().get(0);
                return first1.compareTo(first2);
            }
        });

        return sortedEntries;
    }

    public void run() {
        Thread thread1 = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    stdin = new BufferedReader(new InputStreamReader(System.in));
                    System.out.print("ECSClient> ");

                    try {
                        String cmdLine = stdin.readLine();
                        ECSClient.this.handleCommand(cmdLine);
                    } catch (IOException e) {
                        running = false;
                        logger.error("ECSClient not responding, terminate application.");
                    }
                }
            }
        });

        Thread thread2 = new Thread(new Runnable() {
            @Override
            public void run() {
                if (serverSocket != null) {
                    while (true) {
                        try {
                            // blocked until receiving a connection from a KVServer
                            Socket responseSocket = serverSocket.accept(); // a connection from the server
                            logger.info("Connected to "
                                    + responseSocket.getInetAddress().getHostName()
                                    + " on port " + responseSocket.getPort());
                            ECSClientChild childRunnable = new ECSClientChild(responseSocket, ECSClient.this);
                            Thread childThread = new Thread(childRunnable);
                            ChildObject childObject = new ChildObject();
                            // record response socket and child thread in a pair
                            childObject.childSocket = responseSocket;
                            childObject.childThread = childThread;
                            // map addr:port to runnable
                            childObjects.put(responseSocket.getInetAddress().getHostName() + ":" + responseSocket.getPort(), childObject);
                            // start the child thread
                            childThread.start();

                        } catch (IOException e) {
                            if (running) {
                                logger.warn("Warning! " +
                                        "Unable to establish connection. " +
                                        "Continue listening...\n" + e);
                                break;
                            } else {
                                logger.warn("Warning! " +
                                        "ECS no longer running, Shutting down.\n" + e);
                                closeServerSocket();
                            }

                        } catch (Exception e) {
                            logger.error("Unexpected Error! " +
                                    "Killing..." + e);
                            closeServerSocket();
                        }
                    }
                }
            }
        });

        thread1.start();
        thread2.start();

    }


    public void handleCommand(String cmdLine) {
        String[] tokens = cmdLine.split("\\s+");
    }

    /**
     * Command line options
     */
    private static Options buildOptions() {
        //	Setting up command line params
        Options options = new Options();

        Option port = new Option("p", "port", true, "Sets the port of the server");
        port.setRequired(true);
        port.setType(Integer.class);
        options.addOption(port);

        Option address = new Option("a", "address", true, "Which address the server should listen to, default set to localhost");
        address.setRequired(false);
        address.setType(String.class);
        options.addOption(address);

        Option loglevel = new Option("ll", "loglevel", true, "Loglevel, e.g., INFO, ALL, …, default set to be ALL");
        loglevel.setRequired(false);
        loglevel.setType(String.class);
        options.addOption(loglevel);

        return options;
    }

    public static void main(String[] args) {
        try {
            Options options = buildOptions();

            CommandLineParser parser = new DefaultParser();
            HelpFormatter formatter = new HelpFormatter();
            CommandLine cmd;

            try {
                cmd = parser.parse(options, args);
            } catch (ParseException e) {
                System.out.println(e.getMessage());
                formatter.printHelp("command", options);
                return;
            }

            // Initialize ecs client with command line params
            String address = cmd.getOptionValue("a", "localhost");
            System.out.println(address);
            int port = Integer.parseInt(cmd.getOptionValue("p"));

            ECSClient ecsClient = new ECSClient(address, port);

            String logLevelString = cmd.getOptionValue("ll", "ALL");
            new LogSetup("logs/ecs.log", Level.toLevel(logLevelString));

            ecsClient.initializeServer();
            ecsClient.run();
        } catch (NumberFormatException nfe) {
            System.out.println("Error! Invalid argument <port>! Not a number!");
            System.out.println("Usage: Server <port>!");
            System.exit(1);
        } catch (IOException e) {
            System.out.println("Error! Unable to initialize logger!");
            e.printStackTrace();
            System.exit(1);        }
    }
}
