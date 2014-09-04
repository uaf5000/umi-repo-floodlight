umi-repo-floodlight
===================

This is the controller code for the openflow testbed setup for our lab. I have done changes in Forwarding.java and 
TopologyInstance.java. Changes are made to setup a static tree to install the topology implemented. It is currently a fat tree topology with 20 virtual switches and 16 hosts. Also, code added to the TopologyInstance enable the controller to obtain portstatistics from a switch whenever it receives a new traffic flow at that port. This enables us to calculate number of bytes received by that port so that we can calculate traffic congestion at particular links.
