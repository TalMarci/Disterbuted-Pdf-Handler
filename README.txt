A distributed pdf handler system implemented by Tal Marciano and Eitan Tshernichovsky.

The system receives as an input a list of URLs of PDFs files and a specified task for each of them.
The app connects to Aws services, using s3 buckets, simple queue service, and EC2 instances.
The instances that are used for both manager and workers are T2.MICRO-type computers using the "ami-00e95a9222311e8ed" AMI.
the Local app will use a 'Manager' ec2 instance that will receive requests from multiple users at the same time, and launch ec2 'workers' instances that will perform the required tasks,
 After which they will send the result to the Manager Node that will create a summary file and send it back to the user.

** This project was made in a student environment and got implemented according to our university professor's requirements. 

running the system with an input file containing 2,500 PDFs while choosing the number of workers per PDFs to be 300, took approximately 15 minutes.

To Run the application, follow the next instructions:
1)create 3 s3 buckets called: input-bucket-dsc202, jars-dsc202, output-bucket-dsc202
** if one of the bucket names is occupied, you can give it a different name but you'll need to change the value of the appropriate field in class awsBundle (lines 35-37)
2)create an sqs FIFO queue called "local-manager-queue-id.fifo"
3)To compile the application, the user will have to compile the maven project using the command 'mvn package' from the terminal opened from the root folder of the project.
   To choose a different main change the <artifactId>main class</artifactId>(line8) and <mainClass>instances. (main class)</mainClass>(line 83) in the pom file.
   The user must compile the project 3 times. every time it has to choose a different class as its main (Manager, Worker, LocalApp).
4)Upload the jar files named Worker.jar, and Manager.jar to the jars folder inside the s3 bucket you created.
5)To set the input files, one must put the txt file following the frame described in the assignment 1 pdf inside the target folder.
6)Inside the terminal, change the directory to the target folder and run the line:
   "Java -jar <Name of your mvn compiled local machine app>.jar <input file name> <output file name> <number of PDFs for each worker> [optional: terminate]"
   The app will start working and when it's done, the output file will be downloaded to the Output_Files folder. 

How does it work:
when running the application, the LocalApp is:
1)Generates a unique id for you.
   This id will help to differentiate between different queues, associated with different people running the application at the same time and different files stored in s3 buckets,
2)Sending the id via a queue named "local-manager-queue-id.fifo" that is globally known and is used by every user.
   as well as creating two additional queues named "local-manager-queue-task<unique id>" and "local-manager-queue-result<unique id>"
3)uploading the input file into an S3 bucket named 'input-bucket-dsc202' and sending the key of its location together with the Terminate flag and the number of pdfs per Worker node through "local-manager-queue-task<unique id>".
4)checks if a Manager instance exists, (creates one if not) and waits for the results of the processed PDFs.
5) deletes input and output files from s3.

After being created, the manager is:
1) get into its main loop:
while you didn't receive a termination message:
 pull parse and delete a message from the id-queue, check if it's a terminate type message
 create and run a new thread(MicroManager) to handle the associated User running the LocalApp, for this message.

2)wait for all threads to finish their work and self-terminate

each thread created by the main thread of the manager is:
1)creating 2 queues for tasks and results to communicate with the workers named: "manager-workers-queue-task<unique id>.fifo" and "manager-workers-queue-result<unique id>.fifo"
2)create and send every task and PDF URL in the input file via the task queue.
3)creates workers according to the numbers per workers logic minding not to pass 19 running instances at the same time.
4)running in a loop until receive results for every message sent in section 2:
        pull parse and delete a message from the result queue: this message contains the task performed, the PDF's URL, and the path for the rendered file located in s3/'output-bucket-dsc202'
        add the information to the result string containing the information of the earlier results.
5)send results to the local app via the 'local-manager-queue-result<unique id>' queue
6)stop all workers created by this thread, and close the queues created in section 1.

note that messages are parsed as AppMessage: an Object that holds helpful fields that makes the job easier.

each worker created is running through a loop until the manager stops it:
     poll and parse a message from the manager-workers task queue:
     try to complete the task during the message visibility-Time out.
     if the URL is valid create a success message containing the result, else, create a failure message containing the reason for the failure and send the message to the manager worker result queue.
     delete the task that was pulled from the manager worker queue.

Security: 
every Bucket and SQS can be created as private, meaning you can access it only using the credentials given to you by AWS.
the code does not contain any reference to the access tokens. it is hidden inside the credentials thus making the code safe to use and share.

Scalability: 
in this student environment, we could activate no more than 19 instances at the time, and it seems that AWS is letting us run only 9 of them at the time.
if we ignore this limitation and could run as many instances as we need, our implementation could be very scalable.
the workers are doing all the hard work from different instances.  they don't need a lot of memory since they are not keeping earlier tasks or results.
the manager is implemented in a thread-per-client manner.
its main thread is responsible to pull requests, and every other thread is responsible for creating the tasks and the workers.
for a big number of user requests, we can easily allow defining more than one manager (the assignment requirement allowed us only one) making every part of this app scalable.

Persistence:
the way we implemented the application every task is deleted from the queue if and only if the task was processed and a result message was sent successfully.
if a node died in the middle of processing a certain task, the message will return to be visible 90 seconds after it was received for another node to work on.
by this logic, every task will be taken care of properly.

Tests:
this application was tested using input files of different sizes and handling different scenarios such as more than one request happening simultaneously, different combinations of requests with and without terminated messages, and more.