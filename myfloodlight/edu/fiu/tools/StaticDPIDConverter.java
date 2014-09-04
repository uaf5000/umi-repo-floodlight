package edu.fiu.tools;
import java.math.BigInteger;
import java.util.HashMap;


public class StaticDPIDConverter {
   
   public HashMap<Long,String> longMap;
   public HashMap<String,String> macMap;
   private boolean print = false; //enable Console printing of conversion methods on call.
  
   public StaticDPIDConverter(boolean printFlag)
   {
       macMap = new HashMap<>();
	   longMap = new HashMap<>();
       longMap.put(28429133192619520L, "Switch# 101");
       longMap.put(28710608169330176L, "Switch# 102");
       longMap.put(28992083146040832L, "Switch# 103");
       longMap.put(29273558122751488L, "Switch# 104");
       longMap.put(29555033099526400L, "Switch# 105");
       longMap.put(29836508076237056L, "Switch# 106");
       longMap.put(30117983052947712L, "Switch# 107");
       longMap.put(30399458029658368L, "Switch# 108");
       longMap.put(56576630863685120L, "Switch# 201");
       longMap.put(56858105840395776L, "Switch# 202");
       longMap.put(57139580817106432L, "Switch# 203");
       longMap.put(57421055793817088L, "Switch# 204");
       longMap.put(57702530770592000L, "Switch# 205");
       longMap.put(57984005747302656L, "Switch# 206");
       longMap.put(58265480724013312L, "Switch# 207");
       longMap.put(58546955700723968L, "Switch# 208");
       longMap.put(84724128534750720L, "Switch# 301");
       longMap.put(85005603511461376L, "Switch# 302");
       longMap.put(85287078488236288L, "Switch# 303");
       longMap.put(85568553464946944L, "Switch# 304");
      
       
       generateMACList();
       print = printFlag;
   
    }
  
   void generateMACList(){
       //create a new MAC : SWITCH # pair list from DPID : SWITCH # list

      
       for(Long dpid: longMap.keySet()){
           String temp = Long.toHexString(dpid);
           String append="";
          
           if(temp.length() <16){
        	   for (int x = temp.length(); x < 16; x++){
        		   append +="0";
        	   }
           }
           
           temp =append + temp;
           macMap.put(temp, longMap.get(dpid));
       }  
   }

  
   //convert DPID to SWITCH #
   public String convertDPID(Long dpid){
     if(print)
	   System.out.print("DPID# "+dpid+" -- "+longMap.get(dpid));
     
      return longMap.get(dpid);
   }
  
   public String convertMAC(String mac){
      
       String temp = mac.replaceAll(":","");
       if(print)
    	   System.out.print("MAC# "+mac+" -- "+macMap.get(temp));
       return macMap.get(temp);
   }
 
   public String macToDPID(String mac){
      
       BigInteger bi = new BigInteger(mac,16);
       return bi.toString();
   }
  
   public String dpidToMAc(Long dpid){
      
       return Long.toHexString(dpid);
   }
}