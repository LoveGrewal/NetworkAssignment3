package ca.concordia;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static java.nio.channels.SelectionKey.OP_READ;

public class UDPClient {

	private static final Logger logger = LoggerFactory.getLogger(UDPClient.class);
	private static SocketAddress routerAddress;
	private static InetSocketAddress serverAddress;
	private static String requestType;
	//private static String query;
	private static List<String> postData;
	private static int dataSeqNumber = 1;
	private static DatagramChannel datagramChannel;
	
	//post variables
	private static int latestDataSent=1, latestAckReceived=1;

	private static void runClient(SocketAddress routerAddr, InetSocketAddress serverAddr) throws IOException {
		try (DatagramChannel channel = DatagramChannel.open()) {
			datagramChannel = channel;
			doThreeWayHandshake();
			logger.info("Three Way Hand shake Completed");
			if("POST".equalsIgnoreCase(requestType)) {
				//start sending data
				sendPostData();
				ByteBuffer buf = ByteBuffer.allocate(Packet.MAX_LEN);
				SocketAddress router = datagramChannel.receive(buf);
				buf.flip();
				Packet resp = Packet.fromBuffer(buf);
				String payload = new String(resp.getPayload(), StandardCharsets.UTF_8);
				logger.info("Response: {}", payload);
				
			}else {
				//get function
				//sendGetData();
			}
			
		}
	}

	public static void main(String[] args) throws IOException {
		OptionParser parser = new OptionParser();
		parser.accepts("router-host", "Router hostname").withOptionalArg().defaultsTo("localhost");

		parser.accepts("router-port", "Router port number").withOptionalArg().defaultsTo("3000");

		parser.accepts("server-host", "EchoServer hostname").withOptionalArg().defaultsTo("localhost");

		parser.accepts("server-port", "EchoServer listening port").withOptionalArg().defaultsTo("8007");
		parser.accepts("request-type", "Request Type").withOptionalArg().defaultsTo("post");
		parser.accepts("path", "Path").withOptionalArg().defaultsTo("D:\\NetworksFiles\\C.txt");
		parser.accepts("inline-data", "Inline Data").withOptionalArg().defaultsTo("post");

		OptionSet opts = parser.parse(args);

		// Router address
		String routerHost = (String) opts.valueOf("router-host");
		int routerPort = Integer.parseInt((String) opts.valueOf("router-port"));

		// Server address
		String serverHost = (String) opts.valueOf("server-host");
		int serverPort = Integer.parseInt((String) opts.valueOf("server-port"));

		routerAddress = new InetSocketAddress(routerHost, routerPort);
		serverAddress = new InetSocketAddress(serverHost, serverPort);
		//hard coded data
		requestType = "post";
		//query = "path dude";
		postData = new ArrayList<>();
		postData.add((String) opts.valueOf("path"));
		postData.add("AB ");postData.add("CD ");postData.add("EF ");postData.add("GH ");postData.add("IJ ");postData.add("KL ");postData.add("MN ");postData.add("OP ");

		runClient(routerAddress, serverAddress);
	}

	public static boolean doThreeWayHandshake() throws IOException {
		sendSYN();
		sendACK();
		//logger.info("");
		return true;
	}

	public static void sendSYN() throws IOException {
		try (DatagramChannel channel = DatagramChannel.open()) {
			//datagramChannel = channel;
			do {
				Packet p = new Packet.Builder().setType(Packet.PACKET_TYPE_SYN).setSequenceNumber(0L)
						.setPortNumber(serverAddress.getPort()).setPeerAddress(serverAddress.getAddress())
						.setPayload("".getBytes()).create();
				logger.info("Sending Syn {}", p.toString());
				datagramChannel.send(p.toBuffer(), routerAddress);
			}while(!receiveSYNACK(datagramChannel));
			
			
		}
	}

	public static boolean receiveSYNACK(DatagramChannel channel) throws IOException {
		// Try to receive a packet within timeout.
		datagramChannel.configureBlocking(false);
		Selector selector = Selector.open();
		datagramChannel.register(selector, OP_READ);
		logger.info("Waiting for the response");
		selector.select(5000);

		Set<SelectionKey> keys = selector.selectedKeys();
		if (keys.isEmpty()) {
			logger.error("No syn-ack after timeout");
			return false;
		}
		
		ByteBuffer buf = ByteBuffer.allocate(Packet.MAX_LEN);
		SocketAddress router = datagramChannel.receive(buf);
		buf.flip();
		Packet resp = Packet.fromBuffer(buf);
		logger.info("Receiving Syn Ack {}", resp.toString());
		keys.clear();
		return true;
		
	}

	public static void sendACK() throws IOException {
		try (DatagramChannel channel = DatagramChannel.open()) {
			Packet p = new Packet.Builder().setType(Packet.PACKET_TYPE_ACK).setSequenceNumber(1L)
					.setPortNumber(serverAddress.getPort()).setPeerAddress(serverAddress.getAddress())
					.setPayload("".getBytes()).create();
			logger.info("Ssend  Ack  {}", p.toString()); 
			
			datagramChannel.send(p.toBuffer(), routerAddress);
		}
	}
	
	public static void sendPostData() throws IOException{
		checkWindowAndSendPacket();
		while(latestAckReceived <= postData.size()) {
			checkWindowAndSendPacket();
			try (DatagramChannel channel = DatagramChannel.open()) {
				// Try to receive a packet within timeout.
				datagramChannel.configureBlocking(false);
				Selector selector = Selector.open();
				datagramChannel.register(selector, OP_READ);
				logger.info("Waiting for the data ack");
				selector.select(5000);

				Set<SelectionKey> keys = selector.selectedKeys();
				if (keys.isEmpty()) {
					logger.error("Timeout waitning for data ack");
					sendFirstWindowPacketAgain();
					continue;
				}
				
				ByteBuffer buf = ByteBuffer.allocate(Packet.MAX_LEN);
				SocketAddress router = datagramChannel.receive(buf);
				buf.flip();
				Packet packet = Packet.fromBuffer(buf);
				logger.info("Receive data Ack  {}", packet.toString());
				latestAckReceived = (int) packet.getSequenceNumber();
				keys.clear();
			}
			
		}
		
	}
	
	public static void checkWindowAndSendPacket() throws IOException{
		int currentWindowSize = latestDataSent - latestAckReceived;
		
		while(currentWindowSize < 4 && latestDataSent <= postData.size()) {
			int i = latestDataSent +1;
			//since index starts from 0
			int packetType;
			if(i==9) {
				System.out.println("i:"+i+", postData:"+postData.size());
			}
			if(i==2) {
				packetType = Packet.PACKET_TYPE_DATA_START;
			}else if(i == postData.size()+1) {
				packetType = Packet.PACKET_TYPE_DATA_END;
			}else {
				packetType = Packet.PACKET_TYPE_DATA;
			}
			//dataSeqNumber = dataSeqNumber +1;
			
			//actually send packet
			try (DatagramChannel channel = DatagramChannel.open()) {
				Packet p = new Packet.Builder().setType(packetType).setSequenceNumber(Long.valueOf(i))
						.setPortNumber(serverAddress.getPort()).setPeerAddress(serverAddress.getAddress())
						.setPayload(postData.get((i-2)).getBytes()).create();
				logger.info("Send DataPacket no  {}", p.toString());
				//System.out.print(" Send DataPacket no - "+ i);
				datagramChannel.send(p.toBuffer(), routerAddress);
				latestDataSent = i;
			}
			currentWindowSize = latestDataSent - latestAckReceived;
		}
	}
	
	public static void sendFirstWindowPacketAgain() throws IOException{
		int packetType;
		if((latestAckReceived+1)==2) {
			packetType = Packet.PACKET_TYPE_DATA_START;
		}else if((latestAckReceived+1) == postData.size()+1) {
			packetType = Packet.PACKET_TYPE_DATA_END;
		}else {
			packetType = Packet.PACKET_TYPE_DATA;
		}
		try (DatagramChannel channel = DatagramChannel.open()) {
			Packet p = new Packet.Builder().setType(packetType).setSequenceNumber(Long.valueOf((latestAckReceived+1)))
					.setPortNumber(serverAddress.getPort()).setPeerAddress(serverAddress.getAddress())
					.setPayload(postData.get((((latestAckReceived+1))-1)).getBytes()).create();
			logger.info("Send DataPacket no  {}", p.toString());
			datagramChannel.send(p.toBuffer(), routerAddress);
		}
		
	}
}
