package app_kvECS;

import app_kvServer.KVClientConnection;
import app_kvServer.KVServer;
import ecs.ECSNode;
import ecs.IECSNode;
import logger.LogSetup;
import org.apache.commons.cli.*;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class ECSClient implements IECSClient {
    private static Logger logger = Logger.getRootLogger();
    private String address;
    private int port;
    private boolean running = true;
    private ServerSocket serverSocket;
    private BufferedReader stdin;
    private HashMap<BigInteger, IECSNode> hashRing = new HashMap<>();
    //mapping of KVServers (defined by their IP:Port) and the associated range of hash value
    private HashMap<String, List<BigInteger>> metadata = new HashMap<>();
    private ArrayList<Thread> clients = new ArrayList<Thread>();
    public ECSClient(String address, int port) {
        this.address = address;
        this.port = port;
    }

    private void initializeServer() {
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
     * @param fullAddress IP:port of newly added server
     * @param position the hash of fullAddress, used to determine the position of this node on the hashring
     */
    public void updateMetadataWithNewNode (String fullAddress, BigInteger position) {
        if (metadata.isEmpty()) {
            // add dummy node to handle wrap-around
            List<BigInteger> range1 = Arrays.asList(BigInteger.ZERO, position);
            metadata.put("dummy", range1);

            // insert new node into metadata mapping
            List<BigInteger> range2 = Arrays.asList(position, BigInteger.ZERO);
            metadata.put(fullAddress, range2);
        } else {
            // sort nodes in metadata map by the start of their key-range
            List<Map.Entry<String, List<BigInteger>>> sortedEntries = new ArrayList<>(metadata.entrySet());
            Collections.sort(sortedEntries, new Comparator<Map.Entry<String, List<BigInteger>>>() {
                @Override
                public int compare(Map.Entry<String, List<BigInteger>> e1, Map.Entry<String, List<BigInteger>> e2) {
                    BigInteger first1 = e1.getValue().get(0);
                    BigInteger first2 = e2.getValue().get(0);
                    return first1.compareTo(first2);
                }
            });

            BigInteger prevStart = BigInteger.ZERO;
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

                    inserted = true;
                    break;
                }
                prevStart = rangeStart;
            }

            if (!inserted) {
                // new node is predecessor of dummy node at 0 since it is the one with the largest hash value
                List<BigInteger> range = Arrays.asList(position, prevStart);
                metadata.put(fullAddress, range);

                // cut the range of the successor node, which is the dummy node
                Map.Entry<String, List<BigInteger>> dummy = sortedEntries.get(0);
                List<BigInteger> successorRange = dummy.getValue();
                successorRange.set(1, position);
                metadata.put("dummy", successorRange);
            };
        }
    }

    private BigInteger addNewServerNodeToHashRing(String serverHost, int serverPort) {
        try {
            String fullAddress =  serverHost + ":" + serverPort;

            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(fullAddress.getBytes());
            byte[] digest = md.digest();
            BigInteger hashInt = new BigInteger(1, digest);

            // Create new ECSNode
            String nodeName = "Server:" + fullAddress;
            ECSNode node = new ECSNode(nodeName, serverHost, serverPort);

            hashRing.put(hashInt, node);
            return hashInt;
        } catch (NoSuchAlgorithmException e) {
            logger.error(e);
            throw new RuntimeException(e);
        }
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
                            Socket clientSocket = serverSocket.accept();
                            logger.info("Connected to "
                                    + clientSocket.getInetAddress().getHostName()
                                    + " on port " + clientSocket.getPort());

                            housekeepClientThreads();
                            ServerECSConnection connection =
                                    new ServerECSConnection(clientSocket, ECSClient.this);
                            Thread client = new Thread(connection);
                            clients.add(client);
                            client.start();


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

    private void housekeepClientThreads() {
        Iterator<Thread> iterClients = clients.iterator();
        while (iterClients.hasNext()) {
            Thread client = iterClients.next();
            if (!client.isAlive()) {
                iterClients.remove();
            }
        }
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
            logger.info("ECS started.");

            ecsClient.initializeServer();
            // testing, remove later
            BigInteger one = ecsClient.addNewServerNodeToHashRing("localhost", 5000);
            ecsClient.updateMetadataWithNewNode("localhost" + ":" + 5000, one);
            BigInteger two = ecsClient.addNewServerNodeToHashRing("localhost", 6000);
            ecsClient.updateMetadataWithNewNode("localhost" + ":" + 6000, two);
            BigInteger three = ecsClient.addNewServerNodeToHashRing("localhost", 7000);
            ecsClient.updateMetadataWithNewNode("localhost" + ":" + 7000, three);
            for (Map.Entry<BigInteger, IECSNode> entry : ecsClient.hashRing.entrySet()) {
                BigInteger key = entry.getKey();
                String value = String.valueOf(entry.getValue().getNodePort());
                System.out.println(key + value);
            }
            for (Map.Entry<String, List<BigInteger>> entry : ecsClient.metadata.entrySet()) {
                String key = entry.getKey();
                List<BigInteger> value = entry.getValue();
                System.out.println(key + ": " + value);
            }
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
