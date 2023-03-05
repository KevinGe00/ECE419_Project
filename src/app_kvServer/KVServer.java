package app_kvServer;

import logger.LogSetup;

import org.apache.log4j.Level;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;

import org.apache.commons.cli.*;
import org.apache.log4j.Logger;


public class KVServer extends Thread implements IKVServer {
	public enum Status {
		RUN, 	/* running status */
		CLOSE, 	/* closing status, main way of closing */
		KILL 	/* killing status, should not use this */
	}

	private static Logger logger = Logger.getRootLogger();
	private int port;
	private int cacheSize; 				// not implemented
	private CacheStrategy strategy; 	// not implemented
	private String address;
	private String bootstrapServer;
	private String storeDirectory;
	private String logfilePath;
	private String logLevel;
	private Store store;
	private Socket ECSSocket;
	private ServerSocket serverSocket;
	private Status status;
	private OutputStream output;
	private InputStream input;
	private ArrayList<Thread> clients = new ArrayList<Thread>();

	/**
	 * Start KV Server at given port
	 *
	 * @param port           given port for storage server to operate
	 * @param cacheSize      specifies how many key-value pairs the server is allowed
	 *                       to keep in-memory
	 * @param strategy       specifies the cache replacement strategy in case the cache
	 *                       is full and there is a GET- or PUT-request on a key that is
	 *                       currently not contained in the cache. Options are "FIFO", "LRU",
	 *                       and "LFU".
	 */
	public KVServer(int port, int cacheSize, String strategy) {
		this.port = port;
		// optional caching has not been implemented
		if (cacheSize > 0) {
			logger.warn("Non-zero cache size specified but not implemented.");
		}
		this.cacheSize = cacheSize;
		switch (strategy.toLowerCase()) {
			case "fifo":
				logger.warn("FIFO strategy specified but not implemented.");
				this.strategy = CacheStrategy.LRU;
				break;
			case "lru":
				logger.warn("LRU strategy specified but not implemented.");
				this.strategy = CacheStrategy.LRU;
				break;
			case "lfu":
				logger.warn("LFU strategy specified but not implemented.");
				this.strategy = CacheStrategy.LFU;
				break;
			default:
				this.strategy = CacheStrategy.None;
				break;
		}
	}
	
	public Status getStatus() {
		return status;
	}

	public void setStatus(Status status) {
		this.status = status;
	}

	public void setLogfilePath(String logfilePath){
		this.logfilePath = logfilePath;
		return;
	}

	@Override
	public int getPort(){
		return port;
	}

	public void setAddress(String address) {
		this.address = address;
		return;
	}

	public void setBootstrapServer(String bootstrapServer) {
		this.bootstrapServer = bootstrapServer;
		return;
	}

	@Override
    public String getHostname(){
		return address;
	}

	@Override
    public CacheStrategy getCacheStrategy(){
		return strategy;
	}

	@Override
    public int getCacheSize(){
		return cacheSize;
	}

	@Override
    public boolean inStorage(String key){
		return store.containsKey(key);
	}

	@Override
    public boolean inCache(String key){
		// optional caching has not been implemented
		return false;
	}

	@Override
    public String getKV(String key) throws Exception{
		try {
			String val = this.store.get(key);
			if (val == null) {
				String keyNotFoundErr = "Error! Couldn't find key: " + key + " in store.";
				System.err.println(keyNotFoundErr);
				logger.error(keyNotFoundErr);
				throw new Exception(keyNotFoundErr);
			}
			return val;
		} catch (Exception e) {
			String errMsg = "Error when fetching key: " + key ;
			System.err.println(errMsg);
			logger.error(errMsg, e);
			throw new Exception(errMsg);
		}
	}

	@Override
    public void putKV(String key, String value) throws Exception{
		try {
			this.store.put(key, value);
		} catch (Exception e) {
			String errMsg = "Error when trying to add key-value pair!" ;
			System.err.println(errMsg);
			logger.error(errMsg, e);
			throw new Exception(errMsg);
		}
	}

	@Override
    public void clearCache(){
		// optional caching has not been implemented
		return;
	}

	@Override
    public void clearStorage(){
		this.store.clearStorage();
	}

	@Override
    public void run(){
		// main thread of the server
		connectECS();
		initializeServer();
		
		if (serverSocket != null) {
			while (getStatus() == Status.RUN) {
				try {
					Socket clientSocket = serverSocket.accept();
					logger.info("Connected to " 
						+ clientSocket.getInetAddress().getHostName() 
						+  " on port " + clientSocket.getPort());
					
					housekeepClientThreads();
					KVClientConnection connection = 
						new KVClientConnection(clientSocket, this);
					Thread client = new Thread(connection);
					clients.add(client);
					client.start();
				} catch (IOException e) {
					switch (status) {
						case RUN:
							logger.warn("Warning! " +
								"Unable to establish connection. " +
								"Continue listening...\n" + e);
							break;
						case CLOSE:
							logger.warn("Warning! " +
								"Instructed to close the server. " +
								"Closing...");
							close();
							break;
						case KILL:
							logger.warn("Warning! " +
								"Instructed to kill the server. " +
								"Killing...");
							kill();
							break;
					}
				} catch (Exception e) {
					logger.error("Unexpected Error! " +
						"Killing..." + e);
					kill();
				}
			}
		}
		logger.info("Server stopped.");
	}

	public void connectECS() {
		try {
			ECSSocket = new Socket(String.valueOf(this.bootstrapServer.split(":")[0]), Integer.parseInt(this.bootstrapServer.split(":")[1]));
			output = ECSSocket.getOutputStream();
			input = ECSSocket.getInputStream();

			logger.info("ECS Connection established to: " + bootstrapServer);
		} catch (UnknownHostException e) {
			logger.error("Error! IP address for host '" + String.valueOf(this.bootstrapServer.split(":")[0])
					+ "' cannot be found.");
		} catch (IOException e) {
			logger.error("Error! Cannot open server socket on host: '"
					+ bootstrapServer);
		}
	}

	private void initializeServer() {
    	logger.info("Initialize server ...");
		// find IP for host
		InetAddress bindAddr;
		try {
			bindAddr = InetAddress.getByName(address);
		} catch (UnknownHostException e) {
			logger.error("Error! IP address for host '" + address 
						 + "' cannot be found.");
			setStatus(Status.CLOSE);
			return;
		}
		// create socket with IP and port
    	try {
            serverSocket = new ServerSocket(port, 0, bindAddr);
			serverSocket.setReuseAddress(true);
            logger.info("Server host: " + serverSocket.getInetAddress().getHostName()
					+ " \tport: " + serverSocket.getLocalPort());    
        } catch (IOException e) {
        	logger.error("Error! Cannot open server socket on host: '"
						+ address + "' \tport: " + port);
			closeServerSocket();
			setStatus(Status.CLOSE);
            return;
        }
		setStatus(Status.RUN);
		return;
    }

	/* 
	 * housekeep threads, remove finished threads.
	 * require exceptions to be caught outside this function.
	 */
	private void housekeepClientThreads() {
		Iterator<Thread> iterClients = clients.iterator();
		while (iterClients.hasNext()) {
			Thread client = iterClients.next();
			if (!client.isAlive()) {
				iterClients.remove();
			}
		}
	}

	@Override
    public void close(){
		// Gracefully stop the server, can perform any additional actions
		setStatus(Status.CLOSE);;
		closeServerSocket();
		joinClientThreads();
	}

	@Override
    public void kill(){
		// Abruptly stop the server without any additional actions
		// i.e., do NOT perform saving to storage
		setStatus(Status.KILL);
        closeServerSocket();
		joinClientThreads();
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

	/*
	 * wait for all client threads to complete.
	 * should not be directly called in run().
	 */
	private void joinClientThreads() {
		try{
			Iterator<Thread> iterClients = clients.iterator();
			while (iterClients.hasNext()) {
				Thread client = iterClients.next();
				client.join();
				iterClients.remove();
			}
		} catch (Exception e) {
			logger.error("Unexpected Error! Exception when "
					+ "waiting for all client threads to finish. "
					+ e);
		}
	}

	public void initializeStore(String storeDirectory) {
		this.storeDirectory = storeDirectory;
		this.store = new Store(this.storeDirectory);
	}

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

		Option bootstrapServer = new Option("b", "bootstrapServer", true, "Sets the bootstrap server which is the ECS");
		address.setRequired(true);
		address.setType(String.class);
		options.addOption(bootstrapServer);

		Option directory = new Option("d", "directory", true, "Directory for files (Put here the files you need to persist the data, the directory is created upfront and you can rely on that it exists)");
		directory.setRequired(true);
		directory.setType(String.class);
		options.addOption(directory);

		Option logfilePath = new Option("l", "logfilePath", true, "Relative path of the logfile, e.g., “echo.log”, default set to be current directory\n");
		logfilePath.setRequired(false);
		logfilePath.setType(String.class);
		options.addOption(logfilePath);

		Option loglevel = new Option("ll", "loglevel", true, "Loglevel, e.g., INFO, ALL, …, default set to be ALL");
		loglevel.setRequired(false);
		loglevel.setType(String.class);
		options.addOption(loglevel);

		return options;
	}

	/**
	 * Main entry point for the KVstore server application.
	 * @param args contains the port number at args[0].
	 */
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

			int portVal = Integer.parseInt(cmd.getOptionValue("p"));
			String storeDirectory = cmd.getOptionValue("d");

			KVServer server = new KVServer(portVal, 10, "FIFO");
			server.initializeStore(storeDirectory);

			server.setAddress(cmd.getOptionValue("a", "localhost"));
			server.setBootstrapServer(cmd.getOptionValue("b"));
			server.setLogfilePath(cmd.getOptionValue("l", "."));

			String logLevelString = cmd.getOptionValue("ll", "ALL");
			new LogSetup("logs/server.log", Level.toLevel(logLevelString));

			server.start();
		} catch (IOException e) {
			System.out.println("Error! Unable to initialize logger!");
			e.printStackTrace();
			System.exit(1);
		} catch (NumberFormatException nfe) {
			System.out.println("Error! Invalid argument <port>! Not a number!");
			System.out.println("Usage: Server <port>!");
			System.exit(1);
		}
	}
}
