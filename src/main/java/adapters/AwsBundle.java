package adapters;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.internal.util.EC2MetadataUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.*;

public class AwsBundle {
    public static final int MAX_INSTANCES = 18;
    final String DEFAULT = "default";
    
    private final Ec2Client ec2 = Ec2Client.builder().region(Region.US_EAST_1).build();
    private final S3Client s3 = S3Client.builder().region(Region.US_EAST_1).build();
    private final SqsClient sqs = SqsClient.builder().region(Region.US_EAST_1).build();

    public final static String suffix = ".fifo";
    private final static String postfix_task = "task";
    private final static String postfix_result = "result";
    private final static String postfix_dsc202 = "dsc202";

    public static final String INPUT_BUCKET_NAME = "input-bucket-2-" + postfix_dsc202;
    public static final String RESULT_BUCKET_NAME = "output-bucket-2-" + postfix_dsc202;
    public static final String JARS_BUCKET_NAME = "jars-dsc202-2";
    private static final String LOCAL_MANAGER_QUEUE = "local-manager-queue-";
    private static final String MANAGER_WORKERS_QUEUE = "manager-workers-queue";
    public static final String LOCAL_MANAGER_ID_QUEUE = LOCAL_MANAGER_QUEUE + "id" + suffix;
    public static final String LOCAL_MANAGER_TASK_QUEUE = LOCAL_MANAGER_QUEUE + postfix_task;
    public static final String LOCAL_MANAGER_RESULT_QUEUE = LOCAL_MANAGER_QUEUE + postfix_result;
    public static final String MANAGER_WORKERS_TASK_QUEUE = MANAGER_WORKERS_QUEUE + postfix_task;
    public static final String MANAGER_WORKERS_RESULT_QUEUE = MANAGER_WORKERS_QUEUE + postfix_result;


    public static final String ami = "ami-00e95a9222311e8ed";


    private static final AwsBundle instance = new AwsBundle();
	

    private AwsBundle() {
    }

    public static AwsBundle getInstance() {
        return instance;
    }

    public boolean checkIfInstanceExist(String name) {
        DescribeInstancesRequest request = DescribeInstancesRequest.builder().build();
        DescribeInstancesResponse response = ec2.describeInstances(request);
        for (Reservation r : response.reservations()) {
            for (Instance i : r.instances()) {
                if (i.state().code() != 16)
                    continue;
                for (Tag t : i.tags()) {
                    if (t.key().equals("Name") && t.value().equals(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public String getMangerStatus() {
        DescribeInstancesRequest request = DescribeInstancesRequest.builder().build();
        DescribeInstancesResponse response = ec2.describeInstances(request);
        Tag tagName = Tag.builder().key("name").value("Manager").build();
        for (Reservation reservation : response.reservations()) {
            for (Instance instance : reservation.instances()) {
                if (!instance.state().name().equals("running"))
                    continue;
                List<Tag> tags = instance.tags();
                if (tags.contains(tagName)) {
                    for (Tag t : tags) {
                        if (t.key().equals("Status")) {
                            return t.value();
                        }
                    }
                }
            }
        }
        return null;
    }

    public void createBucketIfNotExists(String bucketName) {
        HeadBucketRequest headBucketRequest = HeadBucketRequest.builder().bucket(bucketName).build();
        try {
            s3.headBucket(headBucketRequest);
            // Because the CreateBucketRequest object doesn't specify a region, the
            // bucket is created in the region specified in the client.
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest
                    .builder()
                    .bucket(bucketName)
                    .createBucketConfiguration(
                            CreateBucketConfiguration.builder()
                                    .locationConstraint(Region.US_EAST_1.id())
                                    .build())
                    .build());
        }
    }

    public void uploadFileToS3(String bucketName, String keyName, File fileName) {

        try {
            PutObjectRequest objectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(keyName)
                    .build();
            RequestBody body = RequestBody.fromFile(fileName);
            s3.putObject(objectRequest, body);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void uploadFileToS3(String bucketName, String keyName, String filePath) {
        try {
            PutObjectRequest objectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(keyName)
                    .build();
            RequestBody body = RequestBody.fromFile(Paths.get(filePath));
            s3.putObject(objectRequest, body);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public InputStream downloadFileFromS3(String bucketName, String keyName) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(keyName)
                    .build();

            return s3.getObject(getObjectRequest);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        return null;
    }

    public void deleteFileFromS3(String bucketName, String key) {
        DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        s3.deleteObject(deleteObjectRequest);
    }

    public void sendMessageWithName(String queueName, String msg) {
        SendMessageRequest send_msg_request = SendMessageRequest.builder()
                .queueUrl(getQueueUrl(queueName))
                // .delaySeconds(10)
                .messageBody(msg)
                .messageGroupId(DEFAULT)
                .messageDeduplicationId(System.currentTimeMillis() + "")
                .build();
        this.sqs.sendMessage(send_msg_request);
    }

    public void sendMessage(String queueUrl, String msg) {
        SendMessageRequest send_msg_request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                // .delaySeconds(10)
                .messageBody(msg)
                .messageGroupId(DEFAULT)
                .messageDeduplicationId(System.currentTimeMillis() + "")
                .build();

        this.sqs.sendMessage(send_msg_request);
    }

    public String createMsgQueue(String queueName) {
        Map<QueueAttributeName, String> attributes = new HashMap<>();
        // attributes.put(QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS,
        // MAX_WAIT_TIME);
        // attributes.put(QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true");

        if (queueName.endsWith(".fifo")) {
            attributes.put(QueueAttributeName.FIFO_QUEUE, "true");
        }
        CreateQueueRequest request = CreateQueueRequest.builder()
                .queueName(queueName)
                .attributes(attributes)
                .build();
        sqs.createQueue(request);

        return this.sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl();
    }

    public void deleteQueue(String queueUrl) {
        try {
            this.sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
        } catch (AwsServiceException e) {
            e.printStackTrace();
            throw e;
        }
    }

    public String getQueueUrl(String queueName) {
        while (true) {
            try {
                GetQueueUrlRequest getQueueUrlRequest = GetQueueUrlRequest.builder().queueName(queueName).build();
                GetQueueUrlResponse queueUrlResponse = sqs.getQueueUrl(getQueueUrlRequest);
                return queueUrlResponse.queueUrl();
            } catch (QueueDoesNotExistException e) {
                break;
            }
        }
        return null;
    }

    public void cleanQueue(String queueUrl) {
        PurgeQueueRequest request = PurgeQueueRequest.builder().queueUrl(queueUrl).build();
        this.sqs.purgeQueue(request);
    }

    public List<Message> fetchNewMessages(String queueUrl, int maxNumberOfMessages) {
        List<Message> messages = new ArrayList<>(0);
        do {
            ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl).visibilityTimeout(90)
                    .attributeNamesWithStrings("MessageGroupId")
                    .maxNumberOfMessages(maxNumberOfMessages)
                    .build();
            messages = this.sqs.receiveMessage(receiveRequest).messages();
        } while (messages.isEmpty());
        return messages;
    }

    public Message fetchNewMessage(String name) {
        List<Message> list = fetchNewMessages(getQueueUrl(name), 1);
        return list.size() != 0 ? list.get(0) : null;
    }

    public void deleteMessageFromQueue(String queueUrl, Message message) {
        this.sqs.deleteMessage(
                DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(message.receiptHandle()).build());
    }

    private void wait(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public String createInstance(String name, String imageId, String userDataScript) {
        IamInstanceProfileSpecification role = IamInstanceProfileSpecification.builder().name("LabInstanceProfile")
                .build();

        RunInstancesRequest runRequest = RunInstancesRequest.builder()
                .imageId(imageId)
                .instanceType(InstanceType.T2_MICRO)
                .maxCount(1)
                .minCount(1)
                .iamInstanceProfile(role)
                .userData(userDataScript)
                .build();

        RunInstancesResponse response = ec2.runInstances(runRequest);
        Instance instance = response.instances().get(0);

        Tag tag = Tag.builder()
                .key("Name")
                .value(name)
                .build();

        wait(1000);
        CreateTagsRequest tagRequest = CreateTagsRequest.builder()
                .resources(instance.instanceId())
                .tags(tag)
                .build();
        ec2.createTags(tagRequest);
        return instance.instanceId();
    }

    public void terminateEc2Instances(List<String> instanceIds) {
        for (String instanceId : instanceIds) {
            TerminateInstancesRequest request = TerminateInstancesRequest.builder()
                    .instanceIds(instanceId).build();
            this.ec2.terminateInstances(request);
        }
    }

    public void terminateCurrentInstance() {
        String instanceId = EC2MetadataUtils.getInstanceId();
        List<String> instanceIds = new ArrayList<>();
        instanceIds.add(instanceId);
        TerminateInstancesRequest request = TerminateInstancesRequest.builder()
                .instanceIds(instanceIds).build();
        this.ec2.terminateInstances(request);
    }

    

}
