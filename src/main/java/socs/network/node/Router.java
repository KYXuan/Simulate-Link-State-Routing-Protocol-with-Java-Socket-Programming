package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;
import socs.network.message.SOSPFPacket;
import socs.network.util.Configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;


public class Router {

  protected LinkStateDatabase lsd;

  RouterDescription rd = new RouterDescription();

  //assuming that all routers are with 4 ports
  Link[] ports = new Link[4];

  public Router(Configuration config) {//Configuration config
    rd.simulatedIPAddress = config.getString("socs.network.router.ip");
    rd.processIPAddress = "127.0.0.1";//real IP address
    rd.processPortNumber = config.getShort("socs.network.router.portNum");
    lsd = new LinkStateDatabase(rd);
    
    try {
    	new Thread(new create_server_Socket(rd.processPortNumber,this)).start();
    }catch(Exception e) {
    	e.printStackTrace();
    }
    
    Timer time= new Timer();
    HeartbeatTask st = new HeartbeatTask();
    time.schedule(st,0, 5000);
    
    
  }
  
  class create_server_Socket implements Runnable{
	  private ServerSocket serverSocket;
	  Router router;
	  public create_server_Socket(int port, Router r) throws IOException {
		  serverSocket = new ServerSocket(port);
		  router = r;
		  System.out.println("created a server socket at port "+serverSocket.getLocalPort()+ "and ip "+ serverSocket.getInetAddress());
	  }
	  
	  public void run() {
//		  System.out.println("entered the run function");
		  while(true) {
			  try {
				  Socket server = serverSocket.accept();
//				  System.out.println("server created!");
				  new Thread(new ClientService(server,router)).start();//handle the message from client
			  }catch(SocketTimeoutException s) {
				  System.out.println("socket times out");
				  break;
			  }catch(Exception e) {
				  e.printStackTrace();
				  break;
			  }
		  }
	  }  
  }
  
  class ClientService implements Runnable{
	  
	  Socket server;
	  Router router;
	  ClientService(Socket s, Router r){
		  server = s;
		  router = r;
	  }

	public void run() {
		ObjectInputStream in = null;
		ObjectOutputStream out = null;
		
		try {
			in = new ObjectInputStream(server.getInputStream());//get the message from the client
			
			SOSPFPacket message = (SOSPFPacket)in.readObject();
//			System.out.println("server: message received from client!");
			if(message.sospfType==0) {//hello message
				System.out.println("Received Hello from "+ message.neighborID);
				//start step 2
				if(!router.isNeighbour(message.neighborID)) {
					router.addNeighbour(message.srcProcessIP, message.srcProcessPort, message.neighborID);
					System.out.println("Set "+message.neighborID + " to INIT state");
				}
				
				out = new ObjectOutputStream(server.getOutputStream());
				SOSPFPacket response = new SOSPFPacket();
				response.sospfType = 0;
				response.neighborID = router.rd.simulatedIPAddress;
				response.srcProcessIP = router.rd.processIPAddress;
				response.srcProcessPort = router.rd.processPortNumber;
				out.writeObject(response);//finish step 2
				
				//start step 4
				message = (SOSPFPacket) in.readObject();
				if(message.sospfType==0) {
					System.out.println("Received hello from "+message.neighborID);
					if ( router.getStatus(message.neighborID)==RouterStatus.INIT) {
						router.setStatus(message.neighborID, RouterStatus.TWO_WAY);
						System.out.println("Set "+message.neighborID + " to TWO WAY state");
					}
					//finish step 4
					
					message = (SOSPFPacket) in.readObject();// receive a LSA msg from client
					
					//start step 5
					if(message.sospfType == 1) {
						LSA newLSA = router.lsd._store.get(router.rd.simulatedIPAddress);//get the current lsa for the server
						newLSA.lsaSeqNumber++;
						
						LinkDescription newLink = new LinkDescription();
						newLink.linkID = message.neighborID;
						newLink.portNum = router.getNeighborPort(message.neighborID);// find which port and place that the neighbour is on
						int weight = -1;
						LSA lsa = message.lsaArray.elementAt(0);// TODO
						for(LinkDescription l: lsa.links) {
							if(l.linkID.equals(router.rd.simulatedIPAddress)){//find the server's link
								weight = l.tosMetrics;
								break;
							}
						}
						newLink.tosMetrics = weight;
						
						newLSA.links.add(newLink);//add a new link with the specified information into our links
						router.lsd._store.put(router.rd.simulatedIPAddress, newLSA);//update our lsd
						
						//TODO
						router.LSAUpdate();
					}
					System.out.print(">> ");

				}
					
			}
			else if(message.sospfType == 1){// if this is an LSA update
				Vector<LSA> lsaArray = message.lsaArray;
				for(LSA lsa: lsaArray) {
					if(router.lsd._store.containsKey(lsa.linkStateID)) {
						if(router.lsd._store.get(lsa.linkStateID).lsaSeqNumber<lsa.lsaSeqNumber) {
							router.lsd._store.put(lsa.linkStateID, lsa);
							router.LSAUpdate(message.srcIP);
						}
					}else {
						router.lsd._store.put(lsa.linkStateID, lsa);
						
						router.LSAUpdate(message.srcIP);
					}
				}
			}else if(message.sospfType == 2) {
				router.removeFromLSD(message.neighborID);
				LSAUpdate();
				router.removeNeighbor(message.neighborID);		
			}
			
		}catch(Exception e) {
		}finally {
			try {
				out.close();
				in.close();
				server.close();
			}catch(Exception e) {
			}
		}
		
	}
	  
  }

  private void LSAUpdate() {
	  for(int i = 0; i< ports.length; i++) {
		  if(ports[i] != null) {
			  String serverName = ports[i].router2.processIPAddress;
			  int port = ports[i].router2.processPortNumber;
			  SOSPFPacket packet = new SOSPFPacket();
			  packet.sospfType = 1;
			  packet.srcIP = rd.simulatedIPAddress;
			  packet.dstIP = ports[i].router2.simulatedIPAddress;
			  Vector<LSA> lsaVector = new Vector<LSA>();
			  for(LSA lsa: lsd._store.values()) {
				  lsaVector.add(lsa);
			  }
			  packet.lsaArray = lsaVector;
			  new Thread(new Client(serverName, port, packet)).start();
		  }
	  }
  }
  
  private void LSAUpdate(String neighbor) {//broadcasts an LSA update to all neighbor except the one passed as input
	  for(int i = 0; i< ports.length; i++) {
		  if(ports[i]!=null && !(ports[i].router2.simulatedIPAddress.equals(neighbor))) {
			  String serverName = ports[i].router2.processIPAddress;
			  int port = ports[i].router2.processPortNumber;
			  SOSPFPacket packet = new SOSPFPacket();
			  packet.sospfType = 1;
			  packet.srcIP = rd.simulatedIPAddress;
			  packet.dstIP = ports[i].router2.simulatedIPAddress;
			  Vector<LSA> lsaVector = new Vector<LSA>();
			  
			  for(LSA lsa: lsd._store.values()) {
				  lsaVector.add(lsa);
			  }
			  
			  packet.lsaArray = lsaVector;
			  new Thread(new Client(serverName, port, packet)).start();
		  }
	  }
  }
  
  public int getNeighborPort(String neighbor) {
	  int portid  =-1;
	  for(int i = 0; i<ports.length; i++) {
		  if(ports[i] != null) {
			  if(ports[i].router2.simulatedIPAddress.equals(neighbor)) {
				  portid = i;
				  break;
			  }
		  }
	  }
	  return portid;
  }
  
  public boolean isNeighbour(String simIpAddress) {
	  int i = 0;
	  for(i = 0; i<ports.length;i++) {
		  if(ports[i]!=null) {
				if(ports[i].router2.simulatedIPAddress.equals(simIpAddress))
					return true;
			}
	  }
	  return false;
  }
  
  public void addNeighbour(String processIP, short processPort, String simulatedIP) {
	  int i = 0;
	  for(i = 0; i< ports.length; i++) {
		  if ( ports[i]==null) {
			  RouterDescription remoteRouter = new RouterDescription();
			  remoteRouter.processIPAddress = processIP;
			  remoteRouter.processPortNumber = processPort;
			  remoteRouter.simulatedIPAddress = simulatedIP;
			  remoteRouter.status = RouterStatus.INIT;
			  ports[i] = new Link(this.rd,remoteRouter);
			  break;
		  }
	  }
  }
  
  class Client implements Runnable {
      private String serverName;
      private int port;
      private SOSPFPacket packet;
      public Client(String serverName,int port,SOSPFPacket packet) {
          this.serverName=serverName;
          this.port=port;
          this.packet=packet;
      }
      public void run() {
			OutputStream outToServer = null;
			ObjectOutputStream out = null;
			InputStream inFromServer = null;
			ObjectInputStream in = null;
			Socket client = null;
          try {

              if (packet.sospfType == 0) {	//sending out a HELLO message
            	  client=new Socket(serverName, port); 
                  outToServer=client.getOutputStream();
                  out = new ObjectOutputStream(outToServer);
                  out.writeObject(packet); //send the initial message to the server
                  inFromServer=client.getInputStream();
                  in = new ObjectInputStream(inFromServer); //wait for a response from the server
                  SOSPFPacket response = (SOSPFPacket) in.readObject(); //receive a response packet from the server
                  if (response.sospfType == 0) { //If it's a HELLO response
                      System.out.println("Received HELLO from " + response.neighborID);
                      if (getStatus(response.neighborID) == RouterStatus.INIT) {
                          setStatus(response.neighborID, RouterStatus.TWO_WAY);
                          System.out.println("Set " + response.neighborID + " state to TWO_WAY");
                      } else if (getStatus(response.neighborID) == null) {
                          setStatus(response.neighborID, RouterStatus.TWO_WAY);
                          System.out.println("Set " + response.neighborID + " state to TWO_WAY");
                      }
                      out.writeObject(packet);
          
                      SOSPFPacket packet2 = new SOSPFPacket();
                      packet2.sospfType = 1; 
                      packet2.neighborID = rd.simulatedIPAddress; 
                      Vector<LSA> lsaVector = new Vector<LSA>(); 
						lsaVector.add(lsd._store.get(rd.simulatedIPAddress));
						packet2.lsaArray = lsaVector;
						out.writeObject(packet2);								
                      System.out.print(">> ");
                  }
              } else if (packet.sospfType == 1) {
            	  client=new Socket(serverName, port); 
                  outToServer=client.getOutputStream();
                  out = new ObjectOutputStream(outToServer);
                  out.writeObject(packet); 

				}else if(packet.sospfType == 2) {
					client = new Socket(serverName, port);
					outToServer = client.getOutputStream();
					out = new ObjectOutputStream(outToServer);
					out.writeObject(packet);
				}
          } catch (Exception e) {
          } finally {
				try {
					out.close();
					in.close();
					client.close();
				} catch (Exception e) {

				}
			}
      }
}
  

  
  public RouterStatus getStatus(String simulated_ip) {
	  int i = 0;
	  for(i = 0; i< ports.length; i++) {
		  if(ports[i]!=null) {
			  if(ports[i].router2.simulatedIPAddress.equals(simulated_ip)) {
				  return ports[i].router2.status;
			  }
		  }
	  }
	  return null;
  }
  
  public void setStatus(String simulated_ip, RouterStatus status) {
	  int i = 0;
	  for(i = 0; i< ports.length; i++) {
		  if(ports[i]!=null) {
			  if(ports[i].router2.simulatedIPAddress.equals(simulated_ip)) {
				  ports[i].router2.status = status;
				  break;
			  }
		  }
	  }
  }

  /**
   * output the shortest path to the given destination ip
   * <p/>
   * format: source ip address  -> ip address -> ... -> destination ip
   *
   * @param destinationIP the ip adderss of the destination simulated router
   */
  private void processDetect(String destinationIP) {
	  System.out.println(lsd.getShortestPath(destinationIP));
  }

  /**
   * disconnect with the router identified by the given destination ip address
   * Notice: this command should trigger the synchronization of database
   *
   * @param portNumber the port number which the link attaches at
   */
  private void processDisconnect(short portNumber) {
	  if(ports[portNumber]!=null) {
		  if(ports[portNumber].router2.status!=null) {
			  String serverName = ports[portNumber].router2.processIPAddress;
			  int port = ports[portNumber].router2.processPortNumber;
			  SOSPFPacket packet = new SOSPFPacket();
			  packet.sospfType = 2;
			  packet.neighborID = rd.simulatedIPAddress;
			  packet.srcProcessIP = rd.processIPAddress;
			  packet.srcProcessPort = rd.processPortNumber;
			  new Thread(new Client(serverName, port, packet)).start();
			  int result = removeFromLSD(ports[portNumber].router2.simulatedIPAddress);
			  if(result == -1) {
				  System.out.println("error when removing from lsd");
			  }
			  LSAUpdate();
			  ports[portNumber] = null;
		  }
	  }
  }

  /**
   * attach the link to the remote router, which is identified by the given simulated ip;
   * to establish the connection via socket, you need to indentify the process IP and process Port;
   * additionally, weight is the cost to transmitting data through the link
   * <p/>
   * NOTE: this command should not trigger link database synchronization
   */
  private void processAttach(String processIP, short processPort,
                             String simulatedIP, short weight) {
	  
	  short i = 0;
	  for(i = 0; i< ports.length; i++) {
		  if ( ports[i]==null) {
			  RouterDescription remoteRouter = new RouterDescription();
			  remoteRouter.processIPAddress = processIP;
			  remoteRouter.processPortNumber = processPort;
			  remoteRouter.simulatedIPAddress = simulatedIP;
			  
			  ports[i] = new Link(this.rd, remoteRouter);
//			  System.out.println("neighbour attached!");
			  
			  LSA new_lsa = lsd._store.get(rd.simulatedIPAddress);//returns a reference
			  new_lsa.lsaSeqNumber++;
			  LinkDescription new_link = new LinkDescription();
			  new_link.linkID = simulatedIP;
			  new_link.portNum = i;
			  new_link.tosMetrics = weight;
			  
			  new_lsa.links.add(new_link);
//			  lsd._store.put(rd.simulatedIPAddress, new_lsa);
			  break;
		  }
	  }
	  if(i==4) {
		  System.out.println("no available port");
	  }

  }

  /**
   * broadcast Hello to neighbors
   */
  private void processStart() {
//	  System.out.println("started");
	  int i = 0;
	  for(i = 0; i< ports.length; i++) {
		  if(ports[i]!=null) {
			  if(ports[i].router2.status==null) {//TODO: may have problem here, server cannot receive message
				  String servername = ports[i].router2.processIPAddress;
				  int port = ports[i].router2.processPortNumber;
				  SOSPFPacket packet = new SOSPFPacket();
				  packet.sospfType=0;
				  packet.neighborID = rd.simulatedIPAddress;
				  packet.srcProcessIP = rd.processIPAddress;
				  packet.srcProcessPort = rd.processPortNumber;
				  new Thread(new Client(servername, port, packet)).start();
			  }
		  }
		  
	  }
	  LSAUpdate();
  }

  /**
   * attach the link to the remote router, which is identified by the given simulated ip;
   * to establish the connection via socket, you need to indentify the process IP and process Port;
   * additionally, weight is the cost to transmitting data through the link
   * <p/>
   * This command does trigger the link database synchronization
   */
  private void processConnect(String processIP, short processPort, String simulatedIP, short weight) {
	  processAttach(processIP, processPort, simulatedIP, weight);
	  processStart();
  }

  /**
   * output the neighbors of the routers
   */
  private void processNeighbors() {
	  int i = 0;
	  for(i = 0; i< ports.length; i++) {
		  if(ports[i]!=null) {
			  if(ports[i].router2.status!=null) {
				  System.out.println("Ip Address of the neightbour "+i+" is : "+ ports[i].router2.simulatedIPAddress);
			  }
		  }
		  
	  }
  }

  /**
   * disconnect with all neighbors and quit the program
   */
  private void processQuit() {
	  System.out.println("Exiting");
	  System.exit(0);
  }
  
  class HeartbeatTask extends TimerTask{

	@Override
	public void run() {
		// TODO Auto-generated method stub
		for(int i = 0; i< ports.length; i++) {
			if(ports[i] != null) {
				if(ports[i].router2.status != null) {
					String simulatedIp = ports[i].router2.simulatedIPAddress;
					String serverName = ports[i].router2.processIPAddress;
					int port = ports[i].router2.processPortNumber;
					Socket client = new Socket();
					try {
						client.connect(new InetSocketAddress(serverName,port), 10000);
					}catch(IOException e) {
						System.out.println(simulatedIp+" is quit");
						int removePorts = removeNeighbor(simulatedIp);
						if(removePorts==-1) {
							System.out.println("error when removing neighbors");
						}
						
						int removeLSD = removeFromLSD(simulatedIp);
						if(removeLSD==-1) {
							System.out.println("error when removing from lsd");
						}
						
						int removeNeighbor= removeFromNeighbor(simulatedIp);
						if(removeNeighbor==-1) {
							System.out.println("error when removing from neighbor");
						}
						
						LSAUpdate();
					}
				}
			}
		}
	}
	  
  }
  
  public int removeNeighbor(String simulatedIPAddress) {
	  int i = 0;
	  for(i = 0; i< ports.length; i++) {
		  if(ports[i]!=null) {
			  if(ports[i].router2.simulatedIPAddress.equals(simulatedIPAddress)) {
				  ports[i] = null;
				  return 1;
			  }
		  }
	  }
	return -1;	  
  }
  
  public int removeFromLSD(String ipToRemove) {
	  LSA ownLSA = lsd._store.get(rd.simulatedIPAddress);
	  for(LinkDescription l: ownLSA.links) {
		  if(l.linkID.equals(ipToRemove)) {
			  ownLSA.links.remove(l);
			  ownLSA.lsaSeqNumber++;
			  return 1;
		  }  
	  }
	  return -1;
  }
  
  public int removeFromNeighbor(String neighbor) {
	  LSA neighborLSA = lsd._store.get(neighbor);
	  for(LinkDescription l: neighborLSA.links) {
		  if(l.linkID.equals(rd.simulatedIPAddress)) {
			  neighborLSA.links.remove(l);
			  neighborLSA.lsaSeqNumber++;
			  return 1;
		  }
	  }
	  return -1;
  }

  public void terminal() {
    try {
      InputStreamReader isReader = new InputStreamReader(System.in);
      BufferedReader br = new BufferedReader(isReader);
      System.out.print(">> ");
      String command = br.readLine();
      while (true) {
        if (command.startsWith("detect ")) {
          String[] cmdLine = command.split(" ");
          processDetect(cmdLine[1]);
        } else if (command.startsWith("disconnect ")) {
          String[] cmdLine = command.split(" ");
          processDisconnect(Short.parseShort(cmdLine[1]));
        } else if (command.startsWith("quit")) {
          processQuit();
        } else if (command.startsWith("attach ")) {
          String[] cmdLine = command.split(" ");
          processAttach(cmdLine[1], Short.parseShort(cmdLine[2]),
                  cmdLine[3], Short.parseShort(cmdLine[4]));
        } else if (command.equals("start")) {
          processStart();
        } else if (command.startsWith("connect ")) {
          String[] cmdLine = command.split(" ");
          processConnect(cmdLine[1], Short.parseShort(cmdLine[2]),
                  cmdLine[3], Short.parseShort(cmdLine[4]));
        } else if (command.equals("neighbors")) {
          //output neighbors
          processNeighbors();
        } else {
          //invalid command
        	System.out.println("ha");
          break;
        }
        System.out.print(">> ");
        command = br.readLine();
      }
      isReader.close();
      br.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

}
