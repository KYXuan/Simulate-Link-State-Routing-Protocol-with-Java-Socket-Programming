
package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;
import java.util.LinkedList;

import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;

public class LinkStateDatabase {

  //linkID => LSAInstance
  HashMap<String, LSA> _store = new HashMap<String, LSA>();

  private RouterDescription rd = null;

  public LinkStateDatabase(RouterDescription routerDescription) {
    rd = routerDescription;
    LSA l = initLinkStateDatabase();
    _store.put(l.linkStateID, l);
  }

  /**
   * output the shortest path from this router to the destination with the given IP address
   */  
  public String getShortestPath(String destinationIP) {
    Set<String> queue = new HashSet<String>();
    HashMap<String, Integer> dist = new HashMap<String, Integer>();
    HashMap<String, String> prev = new HashMap<String, String>();
    for (String s : _store.keySet()) {//initialize all nodes' info
		if (_store.get(s).links.size() == 1) {
		} else {
		dist.put(s, Integer.MAX_VALUE);
		prev.put(s, null);
		queue.add(s);
		}
    }
    dist.put(rd.simulatedIPAddress, 0);//root to itself is 0
    while (!queue.isEmpty()) {
        String tmp = null;
        int minVal = Integer.MAX_VALUE;
        for (String s : queue) {
            if (dist.get(s) < minVal) {
                minVal = dist.get(s);
                tmp = s;
            }
        }
        queue.remove(tmp);//root itself
        for (LinkDescription l : _store.get(tmp).links) {//traverse through neighbors
        	
            if (!queue.contains(l.linkID)) {
                continue;
            } else {
                int alt = dist.get(tmp) + l.tosMetrics;
                if (alt < dist.get(l.linkID)) {//update neighbor's distance
                    dist.put(l.linkID, alt);
                    prev.put(l.linkID, tmp);
                }
            }
        }
    }
    StringBuilder stringBuilder = new StringBuilder();
    String destination = destinationIP;
    if (prev.get(destination) != null || destination.equals(rd.simulatedIPAddress)) {// dest is root
        stringBuilder.insert(0, destination);
    }
    while (prev.get(destination) != null) {
        String previous = prev.get(destination);
        int length = 0;
        for (LinkDescription l : _store.get(previous).links) {
            if (l.linkID.equals(destination)) {
                length = l.tosMetrics;
            }
        }
        stringBuilder.insert(0, " ->(" + length + ") ");
        stringBuilder.insert(0, previous);
        destination = previous;
    }
    return stringBuilder.toString();
  }

  //initialize the linkstate database by adding an entry about the router itself
  private LSA initLinkStateDatabase() {
    LSA lsa = new LSA();
    lsa.linkStateID = rd.simulatedIPAddress;
    lsa.lsaSeqNumber = Integer.MIN_VALUE;
    LinkDescription ld = new LinkDescription();
    ld.linkID = rd.simulatedIPAddress;
    ld.portNum = -1;
    ld.tosMetrics = 0;
    lsa.links.add(ld);
    return lsa;
  }


  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (LSA lsa: _store.values()) {
      sb.append(lsa.linkStateID).append("(" + lsa.lsaSeqNumber + ")").append(":\t");
      for (LinkDescription ld : lsa.links) {
        sb.append(ld.linkID).append(",").append(ld.portNum).append(",").
                append(ld.tosMetrics).append("\t");
      }
      sb.append("\n");
    }
    return sb.toString();
  }

}

