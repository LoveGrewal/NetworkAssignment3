package ca.concordia;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;

public class UDPServer {

    private static final Logger logger = LoggerFactory.getLogger(UDPServer.class);
    private static DatagramChannel dataGramChannel;
    private static SocketAddress router;
    private static ByteBuffer buf;
    
    //specific to post
    private static List<String> postPayload;
    private static HashMap<Integer, String>  postPayloadMap= new HashMap<Integer, String>();
    private static int seqOfDataEnd=0;

    private void listenAndServe(int port) throws IOException {

        try (DatagramChannel channel = DatagramChannel.open()) {
        	dataGramChannel = channel;
            channel.bind(new InetSocketAddress(port));
            logger.info("EchoServer is listening at {}", channel.getLocalAddress());
            buf = ByteBuffer
                    .allocate(Packet.MAX_LEN)
                    .order(ByteOrder.BIG_ENDIAN);

            for (; ; ) {
                buf.clear();
                router = channel.receive(buf);

                // Parse a packet from the received raw data.
                buf.flip();
                Packet packet = Packet.fromBuffer(buf);
                buf.flip();

                String payload = new String(packet.getPayload(), UTF_8);
                //logger.info("Packet: {}", packet);
                //logger.info("Payload: {}", payload);
                //logger.info("Router: {}", router);

                // Send the response to the router not the client.
                // The peer address of the packet is the address of the client already.
                // We can use toBuilder to copy properties of the current packet.
                // This demonstrate how to create a new packet from an existing packet.
                /*Packet resp = packet.toBuilder()
                        .setPayload(payload.getBytes())
                        .create();
                channel.send(resp.toBuffer(), router);*/
                checkPacketTypeAndSendResponse(packet);

            }
        }
    }

    public static void main(String[] args) throws IOException {
        OptionParser parser = new OptionParser();
        parser.acceptsAll(asList("port", "p"), "Listening port")
                .withOptionalArg()
                .defaultsTo("8007");

        OptionSet opts = parser.parse(args);
        int port = Integer.parseInt((String) opts.valueOf("port"));
        UDPServer server = new UDPServer();
        server.listenAndServe(port);
    }
    
    public static void checkPacketTypeAndSendResponse(Packet packet) throws IOException{
    	String payload = new String(packet.getPayload(), UTF_8);
    	if(packet.getType() == Packet.PACKET_TYPE_SYN) {
    		//send Syn-Ack
    		Packet resp = packet.toBuilder()
                    .setType(Packet.PACKET_TYPE_SYN_ACK)
    				.create();
    		logger.info("Sending Syn Ack {}", resp.toString());
    		dataGramChannel.send(resp.toBuffer(), router);
    	}else if(packet.getType() == Packet.PACKET_TYPE_ACK) {
    		//prepare sliding window
    		logger.info("Receive Ack from Client {}", packet.toString());
    		
    	}else if(packet.getType() == Packet.PACKET_TYPE_DATA_START) {
    		logger.info("Start packet received {}", packet.toString());
    		Packet ackForFirstPacket = packet.toBuilder()
                    .setType(Packet.PACKET_TYPE_ACK)
                    .setPayload("".getBytes())
    				.create();
    		logger.info("Sending ack for data start packet {}", ackForFirstPacket.toString());
    		
    		dataGramChannel.send(ackForFirstPacket.toBuffer(), router);
    		List<String> payloadList = receiveDataPacketAndTillDataEndAndReturnPayload();
    		StringBuilder data = new StringBuilder("");
    		for(String s : payloadList) {
    			data.append(s);
    		}
    		String response = callApplicationLayer("post", payload, data.toString());
    		Packet resp = packet.toBuilder()
                    .setType(Packet.PACKET_TYPE_RESPONSE)
                    .setPayload(response.getBytes())
    				.create();
    		logger.info("Sending Response of post call {}", response.toString());
    		dataGramChannel.send(resp.toBuffer(), router);
    	}
    }
    
    public static List<String> receiveDataPacketAndTillDataEndAndReturnPayload() throws IOException {
    	
    	postPayload = new ArrayList<>();
    	Packet packet = null;
    	HashMap<Integer, Packet>  notExpectedSequences= new HashMap<Integer, Packet>();
    	//HashMap<Integer, String>  postPayloadMap= new HashMap<Integer, String>();
    	int expectedSequence = 3;
    	do{
    		buf.clear();
    		router = dataGramChannel.receive(buf);

            // Parse a packet from the received raw data.
            buf.flip();
            packet = Packet.fromBuffer(buf);
            logger.info("Data packet received {}", packet.toString());
            String data = new String(packet.getPayload(), UTF_8);
            //postPayload.add(data);
            postPayloadMap.put((int)packet.getSequenceNumber(), data);
            if(packet.getSequenceNumber() != expectedSequence) {
            	notExpectedSequences.put((int)packet.getSequenceNumber(), packet);
            }
            
            expectedSequence = sendDataAckIfNeeded(expectedSequence, packet, notExpectedSequences);
            if(packet.getType() == Packet.PACKET_TYPE_DATA_END) {
            	seqOfDataEnd = (int)packet.getSequenceNumber();
            }
            
    	}while(packet.getType() != Packet.PACKET_TYPE_DATA_END);
           
        for(int i = 3; i <= seqOfDataEnd; i++) {
        	postPayload.add(postPayloadMap.get(i));
        }
    	return postPayload;
    }
    
    public static int sendDataAckIfNeeded(int expectedSequence, Packet packet, HashMap<Integer, Packet>  notExpectedSequences) throws IOException{
    	if(expectedSequence == (int)packet.getSequenceNumber()) {
    		expectedSequence += 1;
    		//check which ack to send
    		
    		while(notExpectedSequences.get(expectedSequence) != null) {
    			
    			String data = new String(notExpectedSequences.get(expectedSequence).getPayload(), UTF_8);
    			//postPayload.add(data);
    			postPayloadMap.put((int)packet.getSequenceNumber(), data);
    			expectedSequence += 1;
    		}
    		//in case of null the expected sequence is 
    		//send ack
            Packet resp = packet.toBuilder()
                    .setType(Packet.PACKET_TYPE_ACK)
                    .setPayload("".getBytes())
                    .setSequenceNumber(expectedSequence-1)
    				.create();
            logger.info("Sending Ack {}", resp.toString());
    		dataGramChannel.send(resp.toBuffer(), router);
    		
    	}else if(expectedSequence > (int)packet.getSequenceNumber()) {
    		//send ack
            Packet resp = packet.toBuilder()
                    .setType(Packet.PACKET_TYPE_ACK)
                    .setPayload("".getBytes())
                    .setSequenceNumber(expectedSequence-1)
    				.create();
            logger.info("Sending Ack {}", resp.toString());
    		dataGramChannel.send(resp.toBuffer(), router);
    	}
    	return (expectedSequence);
    	
    }
    
    
    
    public static String callApplicationLayer(String type, String query, String data) {
    	if("POST".equalsIgnoreCase(type)) {
    		System.out.println("Application Layer : " + data + ", type : "+ type + ", query : " + query);
    		saveFileContent(query, data);
    		return "OK";
    	}else if("GET".equalsIgnoreCase(type)) {
    		return "OK";
    	}
    	return "";
    }
    
    public static void saveFileContent(String path, String inlineData) {
		// writes the contents
		try {
			logger.info("Saving data in file - " + path);
			FileWriter file = new FileWriter(path, true);
			BufferedWriter bufferedWriter = new BufferedWriter(file);
			bufferedWriter.append(System.lineSeparator() + inlineData);
			bufferedWriter.close();
		} catch (IOException e) {
			logger.info("Saving unsuccessful");
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	
}