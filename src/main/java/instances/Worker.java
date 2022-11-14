package instances;

import java.io.File;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.tools.imageio.ImageIOUtil;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripperByArea;

import adapters.AwsBundle;


import models.AppMessage;

import java.awt.*;
import java.io.InputStream;
import java.net.URL;


import java.io.FileWriter;
import java.awt.image.BufferedImage;
import java.io.IOException;

import software.amazon.awssdk.services.sqs.model.Message;


public class Worker {

    private static AwsBundle awsBundle = AwsBundle.getInstance();
    private static String taskQueueName ="";
    private static String resultQueueName = "";
    private static String workerUuid = String.valueOf(System.currentTimeMillis());
    private static final String CONNECTION_TIMEOUT = "1000";

    

    private static String fix_url(String url){
        //return url.charAt(4)==':'?"https" + url.substring(4) : url;   
        return url;
    }
    
    private static AppMessage parseMsg(String msg){
        AppMessage message = new AppMessage();
        String[] msgBody = msg.split(",");
        message.setType(msgBody[0]);
        message.setFilePath(fix_url(msgBody[1]));
        return message;
    }
    




    private static String createSuccessMsg(AppMessage msg){
        return msg.getType() + ","  + msg.getFilePath() + "," + msg.getResultPath();
    }

    private static String createFailMsg(AppMessage msg){
        return msg.getType() + "," + msg.getFilePath() + "," + msg.getFailureMsg();
    }
    private final static PDDocument loadPDF (AppMessage message) throws IOException {
        try{
            String url = message.getFilePath();
            InputStream inputBytes = new URL(url).openStream();
            return PDDocument.load(inputBytes);
            
        }catch(IOException err){
            throw err;
        }
    }
   
    private static void updateSuccessMessage(AppMessage msg, String key){
        msg.setResultPath(key);
        msg.setStatus(AppMessage.Status.COMPLETE);
    }

    private static void updateFailMessage(AppMessage msg, String failMsg){
        msg.setStatus(AppMessage.Status.FAILED);
        msg.setFailureMsg(failMsg);
    }

    private static File createTextOrHtmlFile(String format, String fileName, String text) throws IOException{

        File file;
        
        if(format.equals("ToText")) 
            file = new File(fileName+".txt");
        else {
            file = new File(fileName+".html");
            String[] lines = text.split("\n");
            text="";
            for(String line:lines){
                text=text+line+"<br>\n";
            }
            text= "<!DOCTYPE html>\n<html>\n<body>\n\n<p>"+text+"</p>\n\n</body>\n</html>"; 
         }
        FileWriter writer = new FileWriter(file);
        writer.write(text);  writer.flush(); writer.close();   

        return file; 
    }

    private static void sendSqsMessage(AppMessage message, String queueName){
    if(message.getStatus() == AppMessage.Status.FAILED){
        awsBundle.sendMessage(awsBundle.getQueueUrl(queueName), createFailMsg(message));
    }
    else{
        awsBundle.sendMessage(awsBundle.getQueueUrl(queueName), createSuccessMsg(message));
    }
    }

    private static void pdfTo_Text_Html(AppMessage message){
        try {
           // Technical PDFBox assume for now that it works.
            PDDocument pdfDoc = loadPDF(message);
            // Stripping the text from the file
            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            stripper.setSortByPosition(true);
            Rectangle rect = new Rectangle(0, 0, 10000000, 1000000);
            stripper.addRegion("FULL", rect);
            PDPage firstPage = pdfDoc.getPage(0);
            stripper.extractRegions(firstPage);
            String text = stripper.getTextForRegion("FULL");

            // Create Write  file
            File output = createTextOrHtmlFile(message.getType(),"output" + workerUuid , text);
            

            //Update message + Upload To S3
            String key = "output/" + String.valueOf(System.currentTimeMillis());
            awsBundle.uploadFileToS3(AwsBundle.RESULT_BUCKET_NAME, key, output);

            updateSuccessMessage(message, key);
            

        } catch (IOException err){
            updateFailMessage(message, "Exception while trying to convert pdf document - " + err.getMessage());
        }
    }

    private static void pdfToImg(AppMessage message){
        try {
           // Technical PDFBox assume for now that it works. 
            PDDocument pdfDoc = loadPDF(message);
            // Render PDF to Image
            PDFRenderer pdfRenderer = new PDFRenderer(pdfDoc);
            BufferedImage buff = pdfRenderer.renderImageWithDPI(0, 300, ImageType.RGB);

            // Create and load image to file
            String fileName = workerUuid + "image.png";
            ImageIOUtil.writeImage(buff, fileName, 300);
            pdfDoc.close();
            File output = new File(fileName);

            // Update message
            String key = "output" + "/" + String.valueOf(System.currentTimeMillis());
            awsBundle.uploadFileToS3(AwsBundle.RESULT_BUCKET_NAME, key, output);
            updateSuccessMessage(message, key);
            

        } catch (IOException err){
            updateFailMessage(message, "Exception while trying to convert pdf document - " + err.getMessage());
       }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.client.defaultConnectTimeout", CONNECTION_TIMEOUT);

        String uniqueMmID= args[0];
        taskQueueName = AwsBundle.MANAGER_WORKERS_TASK_QUEUE + uniqueMmID + AwsBundle.suffix;
            resultQueueName = AwsBundle.MANAGER_WORKERS_RESULT_QUEUE + uniqueMmID + AwsBundle.suffix;
        while(true){
            Message msg = awsBundle.fetchNewMessage(taskQueueName);
            if(msg != null){
                AppMessage task = parseMsg(msg.body());
                if(task.getType().equals("ToImage"))
                        pdfToImg(task);
                
                else if(task.getType().equals("ToText") | task.getType().equals("ToHTML"))
                    pdfTo_Text_Html(task);

                //type is faulty  
                else{
                    task.setFailureMsg("task description is unknown.");
                }
                awsBundle.deleteMessageFromQueue(awsBundle.getQueueUrl(taskQueueName),msg);
                sendSqsMessage(task, resultQueueName);
            }
        }
    }

}
