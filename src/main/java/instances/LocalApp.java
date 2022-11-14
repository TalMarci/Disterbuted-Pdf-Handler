package instances;

import adapters.AwsBundle;

import java.io.*;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import software.amazon.awssdk.services.sqs.model.Message;


public class LocalApp {

    final static AwsBundle awsBundle = AwsBundle.getInstance();
    private static final int MAX_FILE_SIZE = 10000;
    private static final int WAIT_TIME = 30000;

    public static void main(String[] args) {

        String localUuid =  String.valueOf(System.currentTimeMillis());
        final String key =  args[0] +"/"+ localUuid;
        final String inputFilePath = args[0];
        final String outPathFilePath = args[1];
        boolean shouldTerminate = false;
        boolean gotResult = false;

        if(args.length == 3 || args.length == 4) {
            if (args.length == 4) {
                if (args[3].equals("terminate"))
                    shouldTerminate = true;
                else {
                    System.err.println("Invalid command line argument: " + args[3]);
                    System.exit(1);
                }
            }
        }
        else {
            System.err.println("Invalid number of command line arguments");
            System.exit(1);
        }

        if (!isLegalFileSize(new File(args[0])))
        {
            System.out.println("Input file is over maximal size (10MB)");
            System.exit(1);
        }

        activeManagerIfNotActive();
        

        String taskQueueName = AwsBundle.LOCAL_MANAGER_TASK_QUEUE + localUuid + AwsBundle.suffix ;
        String resultQueueName = AwsBundle.LOCAL_MANAGER_RESULT_QUEUE + localUuid + AwsBundle.suffix;

        awsBundle.createMsgQueue(AwsBundle.LOCAL_MANAGER_ID_QUEUE);
        String LocalToMangerIdQueueUrl = awsBundle.getQueueUrl(AwsBundle.LOCAL_MANAGER_ID_QUEUE);
        String taskQueueUrl = awsBundle.createMsgQueue(taskQueueName);
        String resultQueueUrl = awsBundle.createMsgQueue(resultQueueName);//changed

        awsBundle.uploadFileToS3(AwsBundle.INPUT_BUCKET_NAME, key , inputFilePath);

        String type = shouldTerminate ? "terminate" : "input";
        String reqMsg = createMessage(type, key, args[2]);
        awsBundle.sendMessage(LocalToMangerIdQueueUrl, localUuid);
        awsBundle.sendMessage(taskQueueUrl, reqMsg);//changed
        System.out.println("\nMessage sent");
        
        while(!gotResult){
            wait(WAIT_TIME);
            activeManagerIfNotActive();

            Message message= awsBundle.fetchNewMessage(resultQueueName);
            if (message != null){
                gotResult=true;
                awsBundle.deleteFileFromS3(AwsBundle.INPUT_BUCKET_NAME, key);
                String final_result_key = message.body();
                try {
                    makeHtmlFile(final_result_key,outPathFilePath);
                    awsBundle.deleteMessageFromQueue(resultQueueUrl, message);
                    awsBundle.cleanQueue(resultQueueUrl);
                    awsBundle.deleteQueue(resultQueueUrl);
                    awsBundle.deleteFileFromS3(AwsBundle.RESULT_BUCKET_NAME, final_result_key);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }


    private static void createManager()
    {
        String managerScript = "#! /bin/bash\n" +
                "sudo yum update -y\n" +
                "mkdir ManagerFiles\n" +
                "aws s3 cp s3://"+ AwsBundle.JARS_BUCKET_NAME +"/Manager.jar ./ManagerFiles\n" +
                "java -jar /ManagerFiles/Manager.jar\n";

                
        String encodedManagerScriptBase64= Base64.getEncoder().encodeToString(managerScript.getBytes());

        awsBundle.createInstance("Manager",AwsBundle.ami,encodedManagerScriptBase64);
    }

    private static String createMessage(String type, String key, String n)
    {
        return type + "," + key + "," + n;
    }

    private static List<String> getLines (String key) throws IOException{
        InputStream response = awsBundle.downloadFileFromS3(AwsBundle.RESULT_BUCKET_NAME, key);
        BufferedReader bufferReader= new BufferedReader(new InputStreamReader(response));
        return bufferReader.lines().collect(Collectors.toList());
    }
    
    private static void makeHtmlFile(String key, String outputPath) throws Exception {
        File file = new File(Paths.get(outputPath) + ".html");
        FileWriter writer = new FileWriter(file);
        List<String> lines = getLines(key);// download summary file from bucket.
        writer.write("<ul>");
        for(String line : lines){
            line = "<li>"+ line +"</li>"+"\n";
            writer.write(line);
        }
        writer.write("</ul>");
        writer.flush();
        writer.close();
    }

    // Check if file is larger then 10MB
    private static boolean isLegalFileSize(File file) {
        return (file.length() / 1024) < MAX_FILE_SIZE ;
    }

    private static void activeManagerIfNotActive(){
        if(!awsBundle.checkIfInstanceExist("Manager")){
            createManager();
            System.out.println("Manager activated");
        }
    }

    private static void wait(int ms){
        try{
            Thread.sleep(ms);
        }
        catch(InterruptedException ex){
            Thread.currentThread().interrupt();
        }
    }
}
    
