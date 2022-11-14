package instances;

import java.io.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


import com.fasterxml.jackson.core.JsonProcessingException;


import adapters.AwsBundle;
import models.AppMessage;
import software.amazon.awssdk.services.sqs.model.Message;

public class Manager {
    final static AwsBundle awsBundle = AwsBundle.getInstance();
    private boolean terminateFlag = false;
    private AtomicInteger numWorkers = new AtomicInteger(0);
    private AtomicInteger microManagement = new AtomicInteger(0);
    private final static int WAIT_TIME = 2000;

    private List<String> getLines (String key) throws IOException{
        InputStream response = awsBundle.downloadFileFromS3(AwsBundle.INPUT_BUCKET_NAME, key);
        BufferedReader bufferReader= new BufferedReader(new InputStreamReader(response));
        return bufferReader.lines().collect(Collectors.toList());
    }

    private String makeMessage(String line){
        String[] split = line.split("\\s+"); 
        return split[0] + "," + split[1] ; // [0]-> Type [1] -> file_path
    }
    
    private List<String> createMessages(List<String> lines){
        List<String> messages = new ArrayList<String>();
        for (String line : lines) {
            messages.add(makeMessage(line));
        }    
        return messages;
    }
    private void sendSqsMessage(String message, String queueUrl){
        awsBundle.sendMessage(queueUrl, message);
    }

    private void sendSqsMessages(List<String> messages, String queueUrl){
        int i=0;
        for (String message : messages) {
            sendSqsMessage(message, queueUrl);
            i++;
        }
    }

    private static AppMessage parseTaskMessage(String msg){
        String[] msgBody = msg.split(",");
        AppMessage message = new AppMessage();
        message.setType(msgBody[0]);
        message.setFilePath(msgBody[1]);
        message.setNumOfPDF(Integer.parseInt(msgBody[2]));
        return message;
    }

    private static AppMessage parseResultMsg(String msg){
        String[] msgBody = msg.split(",");
        AppMessage message = new AppMessage();
        message.setType(msgBody[0]);
        message.setFilePath(msgBody[1]);
        message.setResultPath(msgBody[2]);
        return message;
    }

    public static void wait(int ms){
        try{
            Thread.sleep(ms);
        }
        catch(InterruptedException ex){
            Thread.currentThread().interrupt();
        }
    }
    private  void waitForManagers(){
        synchronized (microManagement){
            while(microManagement.get()>0){//waiting for all microServices to finish.
                try{
                    microManagement.wait();
                }
                catch (InterruptedException e){}
            }
        }
    }

    private void createAndRunMicroManger(AppMessage message, String localUuid) {
        Thread microManger = new Thread(new Runnable(){
            List<String> workersId = new ArrayList<String>();
            List<String> results = new ArrayList<String>();;

            public void run(){
                List<String> appMessages; // all the different messages representing the different links in the task
                int completedTasks = 0; // Indicator the check if all the Tasks are completed
                boolean gotAllResults = false; // Flag to check whether all the results are in
                String uniqueMmID = localUuid;
                String mmToWorkersTaskQueueUrl = awsBundle.createMsgQueue(AwsBundle.MANAGER_WORKERS_TASK_QUEUE + uniqueMmID + AwsBundle.suffix);
                String workersToMmResultQueueUrl = awsBundle.createMsgQueue(AwsBundle.MANAGER_WORKERS_RESULT_QUEUE + uniqueMmID + AwsBundle.suffix);
                String resultQueueName = AwsBundle.MANAGER_WORKERS_RESULT_QUEUE + uniqueMmID + AwsBundle.suffix;

                
                try {
                    appMessages = createMessages(getLines(message.getFilePath()));
                    int numOfWorkers = Math.max((appMessages.size()/message.getNumOfPDF()), 1);
                    createWorkers(numOfWorkers, uniqueMmID);
                    sendSqsMessages(appMessages, mmToWorkersTaskQueueUrl);
                    

                    if(message.getType().equals("terminate"));
                        terminate();
                    
                        while(!gotAllResults){
                        Message message = awsBundle.fetchNewMessage(resultQueueName);    
                        String resultMsgBody = message.body();
                       
                        if (resultMsgBody != null){
                            AppMessage resultMsg = parseResultMsg(resultMsgBody);
                            addToResult(resultMsg.getType(), resultMsg.getFilePath(), resultMsg.getResultPath());
                            awsBundle.deleteMessageFromQueue(awsBundle.getQueueUrl(resultQueueName), message);
                            completedTasks++;
                            System.out.println(completedTasks);
                            if(completedTasks == appMessages.size())
                                gotAllResults = true;
                        }
                    }
                    sendResult(uniqueMmID);
                    stopWorkers();
                    closeSqs(mmToWorkersTaskQueueUrl, workersToMmResultQueueUrl);
                    synchronized(microManagement){
                        microManagement.decrementAndGet();
                        microManagement.notifyAll();
                    }

                }catch (IOException e) {
                    System.err.println(e.getLocalizedMessage());
                }            
            }

            private void sendResult(String key) throws IOException {
                File summeryFile= new File("summery.txt");
                FileWriter writer = new FileWriter(summeryFile);
                writer.write(results.toString().replaceAll("[\\[\\]]", "").replaceAll(",", " "));writer.flush();writer.close();
                awsBundle.uploadFileToS3(AwsBundle.RESULT_BUCKET_NAME,key,summeryFile);
                awsBundle.sendMessageWithName(AwsBundle.LOCAL_MANAGER_RESULT_QUEUE  +localUuid + AwsBundle.suffix, key);
            }

            private void closeSqs(String MMTWQ, String WTMMQ) {
                awsBundle.cleanQueue(MMTWQ);
                awsBundle.cleanQueue(WTMMQ);
                awsBundle.deleteQueue(MMTWQ);
                awsBundle.deleteQueue(WTMMQ);
            }

            private synchronized void createWorkers(int numOfWorkers,String microMId){
                for(int i=0 ; i < numOfWorkers; i++){
                    if(numWorkers.get() < AwsBundle.MAX_INSTANCES){
                        numWorkers.getAndIncrement();
                        createWorker(microMId + i);
                    }
                }   
            }

           

            private synchronized void stopWorkers(){
                awsBundle.terminateEc2Instances(workersId);
                numWorkers.getAndSet(numWorkers.get() - workersId.size());
                workersId.clear();
            }

            private void createWorker(String microMId){
                String workerScript = "#! /bin/bash\n" +
                    "sudo yum update -y\n" +
                    "mkdir WorkerFiles\n" +
                    "aws s3 cp s3://"+ AwsBundle.JARS_BUCKET_NAME +"/Worker.jar ./WorkerFiles\n" +
                    "java -jar /WorkerFiles/Worker.jar " + localUuid + "\n";
                String workerEncodedScript=Base64.getEncoder().encodeToString(workerScript.getBytes());
                workersId.add(awsBundle.createInstance("Worker" + microMId, AwsBundle.ami, workerEncodedScript));
            }

            private void addToResult(String op, String input, String output) {
                String result =  op  + "\t" + input + "\t" + output + "\n";
                results.add(result);
            }

    
            private void terminate() {
                terminateFlag = true;
            } 
        });
        microManagement.getAndIncrement();
        microManger.start();
    }

    public static void main(String[] args) throws JsonProcessingException {
        Manager manager= new Manager();
        while(!manager.terminateFlag){//receive request for input files from local apps.
           
                Message idMsg = awsBundle.fetchNewMessage(AwsBundle.LOCAL_MANAGER_ID_QUEUE);
                String localQueueId = idMsg.body();
                awsBundle.deleteMessageFromQueue(awsBundle.getQueueUrl(AwsBundle.LOCAL_MANAGER_ID_QUEUE), idMsg);

                String taskQueueName = AwsBundle.LOCAL_MANAGER_TASK_QUEUE + localQueueId + AwsBundle.suffix;
                String taskQueueUrl = awsBundle.getQueueUrl(taskQueueName);
                String msgBody = awsBundle.fetchNewMessage(taskQueueName).body();

                AppMessage msg = parseTaskMessage(msgBody);
            
                if(msg.getFilePath() == null){
                    wait(WAIT_TIME);
                    continue;
                }

                msg.setStatus(AppMessage.Status.FETCHED);

                if(msg.getType().equals("terminate")){
                    manager.terminateFlag=true;
                }
                manager.createAndRunMicroManger(msg,localQueueId);
                awsBundle.cleanQueue(taskQueueUrl);
                awsBundle.deleteQueue(taskQueueUrl);            
        }
        manager.waitForManagers();//wait for all micro managers to finish work.
        awsBundle.terminateCurrentInstance();
    }
}