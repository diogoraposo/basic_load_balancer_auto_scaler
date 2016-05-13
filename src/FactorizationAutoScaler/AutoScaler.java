package FactorizationAutoScaler;


import java.awt.List;
import java.util.ArrayList;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusRequest;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusResult;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceState;
import com.amazonaws.services.ec2.model.InstanceStatus;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesResult;

import FactorizationDB.FactorizationDB_API;
import FactorizationDB.FactorizationElement;


public class AutoScaler {

	private static int period;
	private static AmazonEC2 ec2;
	private static ArrayList<String> instance_ids = new ArrayList<String>();
	private static FactorizationDB_API db_api = new FactorizationDB_API();

	private static long avgcpu_lower = 20000;
	private static long avgcpu_upper = 70000;
	private static int totalbb_lower = 1000;
	private static int totalbb_upper = 100000;
	private static int totalinst_lower = 1000;
	private static int totalinst_upper = 50000;
	private static int active_upper = 5;

	private static void init() throws Exception {
		/*
		 * The ProfileCredentialsProvider will return your [default]
		 * credential profile by reading from the credentials file located at
		 * (~/.aws/credentials).
		 */
		AWSCredentials credentials = null;
		try {
			credentials = new ProfileCredentialsProvider().getCredentials();
		} catch (Exception e) {
			throw new AmazonClientException(
					"Cannot load the credentials from the credential profiles file. " +
							"Please make sure that your credentials file is at the correct " +
							"location (~/.aws/credentials), and is in valid format.",
							e);
		}

		ec2 = new AmazonEC2Client(credentials);
		Region euWest1 = Region.getRegion(Regions.EU_WEST_1);
		ec2.setRegion(euWest1);
	}


	public static void main(String[] args) {
		period = 180000;
		System.out.println("Don't forget to add the credentials file");
		try {
			init();
			int activethreads;
			int avgcpu;
			int totaldynbb;
			int totalinst;
			int fullinstances;
			while(true){

				if(!areAnyActive()){
					System.out.println("Period loop ---> There are no instances running, creating one");
					createInstance();
					continue;
				} else {
					System.out.println("Period loop ---> There are instances running");
				}
				Thread.sleep(period);
				fullinstances = 0;
				ArrayList<String> instance_tmps = (ArrayList<String>) instance_ids.clone();	
				for(String id: instance_tmps){
					activethreads = 0;
					avgcpu = 0;
					totaldynbb = 0;
					totalinst = 0;
					System.out.println("Instance id: " + id);
					for(FactorizationElement element: db_api.getAllProcessInstrumentationData(id)){
						System.out.println("Thread: " + element.getProcessID()
						+ " time_on_cpu: " + element.getTimeOnCpu()
						+ " bb: " + element.getDynNumBB()
						+ " inst: " + element.getDynNumInst());
							avgcpu += element.getTimeOnCpu();
							totaldynbb += element.getDynNumBB();
							totalinst += element.getDynNumInst();
					}
					if((db_api.getAllProcessInstrumentationData(id).size()>0))
							avgcpu = avgcpu/(db_api.getAllProcessInstrumentationData(id).size());
					System.out.println("Avgcpu: " + avgcpu
							+ " totalbb: " + totaldynbb 
							+ " totalinst: " + totalinst);
					statusInstance(id);
					if(avgcpu > avgcpu_upper || totaldynbb > totalbb_upper || totalinst > totalinst_upper || activethreads > active_upper){
						System.out.println("Full cause avgpu: " + (avgcpu > avgcpu_upper) 
								+ " totaldynbb: " + (totaldynbb > totalbb_upper) 
								+ " totalinst: " + (totalinst > totalinst_upper)
								+ " active_threads" + (activethreads > active_upper));
						fullinstances += 1;
					} else if(avgcpu < avgcpu_lower && totaldynbb < totalbb_lower && totalinst < totalinst_lower && activethreads == 0 && instance_ids.size() > 1){
						//						for(String ide : instance_ids){
						//							for(FactorizationElement f: db_api.getAllProcessInstrumentationData(ide)){
						//								System.out.println("key" + f.getProcessID());
						//								System.out.println("DELETING THREAD " + f.getProcessID());
						//								db_api.deleteThread(f.getProcessID(), ide);
						//							}
						//						}
						removeInstance(id);
					} 	
					
				}

				if(fullinstances == instance_ids.size()){
					//					for(String ide2: instance_ids){
					//						for(FactorizationElement f: db_api.getAllProcessInstrumentationData(ide2)){
					//							System.out.println("key" + f.getProcessID());
					//							System.out.println("DELETING THREAD " + f.getProcessID());
					//							db_api.deleteThread(f.getProcessID(), ide2);
					//						}
					//					}
					createInstance();
				}	
			}
		} catch (Exception e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}


	}

	public static void createInstance(){

		System.out.println("Creating a new ec2 instance");
		RunInstancesRequest runInstancesRequest = new RunInstancesRequest();

		runInstancesRequest.withImageId("ami-f8109a8b")
		.withInstanceType("t2.micro")
		.withMinCount(1)
		.withMaxCount(1)
		.withKeyName("precious-thing")
		.withSecurityGroups("yesofficer");

		RunInstancesResult runInstancesResult = ec2.runInstances(runInstancesRequest);

		instance_ids.add(runInstancesResult.getReservation().getInstances().get(0).getInstanceId());
		for(String i: instance_ids){
			System.out.println("Running instance: " + i);
		}
		System.out.println("init");
		db_api.initialize();	
		System.out.println("done");
		db_api.createTable(runInstancesResult.getReservation().getInstances().get(0).getInstanceId());
	}

	public static boolean areAnyActive() {
		if(instance_ids.isEmpty()){
			return false;
		} else {
			return true;
		}	
	}

	public static void removeInstance(String id){
		ArrayList<String> singleIdList = new ArrayList<String>();
		singleIdList.add(id);
		TerminateInstancesRequest termInstanceRequest = new TerminateInstancesRequest();
		termInstanceRequest.setInstanceIds(singleIdList);

		TerminateInstancesResult termInstancesResult = ec2.terminateInstances(termInstanceRequest);

		System.out.println("Terminating instance with id: " + id);
		System.out.println(termInstancesResult.getTerminatingInstances().get(0).getInstanceId());

		db_api.deleteTable(id);
		instance_ids.remove(id);
	}
	
	public static String statusInstance(String id){
		DescribeInstancesRequest describeInstanceRequest = new DescribeInstancesRequest().withInstanceIds(id);
	    DescribeInstancesResult describeInstanceResult = ec2.describeInstances(describeInstanceRequest);
	    InstanceState state = describeInstanceResult.getReservations().get(0).getInstances().get(0).getState();
	    System.out.println("Instace " + id + " is " + state.getCode());
	    return Integer.toString(state.getCode());
	}

}
