package models;


public class  AppMessage {

    public enum Status {
        COMPLETE,
        FAILED,
        SENT,
        FETCHED
    }

    private String type;
    private String filePath;
    private String resultPath;
    private int numOfPDF;
    private Status status;
    private String failureMsg;
    

    public String getFilePath(){
        return this.filePath;
    }

    public void setFilePath(String filePath){
        this.filePath = filePath;
    }

    public String getResultPath(){
        return this.resultPath;
    }


    public void setResultPath(String resultPath){
        this.resultPath = resultPath;
    }

    

    public Status getStatus(){
        return this.status;
    }


    public  void setStatus(Status status){
        this.status = status;
    }

    public String getFailureMsg(){
        return this.failureMsg;
    }


    public void setFailureMsg(String failureMsg){
        this.failureMsg = failureMsg;
    }

    public String getType(){
        return this.type;
    }

    public  void setType(String format){
        this.type = format;
    }

    public int getNumOfPDF(){
        return this.numOfPDF;
    }

    public void setNumOfPDF(int n){
        this.numOfPDF = n;
    }




    @Override
    public String toString() {
        return "{" +
            " filePath='" + getFilePath() + "'" +
            ", resultPath='" + getResultPath() + "'" +
            ", numOfPDF='" + getNumOfPDF() + "'" +
            ", status='" + getStatus() + "'" +
            ", format='" + getType() + "'" +
            ", failureMsg='" + getFailureMsg() + "'" +
        "}";
    }

    
}