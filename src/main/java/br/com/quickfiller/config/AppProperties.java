package br.com.quickfiller.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private int maxUploadMb = 15;
    private long retentionMinutes = 60;
    private int workerThreads = 2;
    private int workerQueue = 20;
    private int pdfMinTextCharacters = 120;
    private String tesseractCommand = "tesseract";
    private String tesseractLanguage = "por+eng";
    private int ocrDpi = 300;
    private long ocrTimeoutSeconds = 90;
    private Path storageDir;

    public int getMaxUploadMb() { return maxUploadMb; }
    public void setMaxUploadMb(int maxUploadMb) { this.maxUploadMb = maxUploadMb; }
    public long getRetentionMinutes() { return retentionMinutes; }
    public void setRetentionMinutes(long retentionMinutes) { this.retentionMinutes = retentionMinutes; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
    public int getWorkerQueue() { return workerQueue; }
    public void setWorkerQueue(int workerQueue) { this.workerQueue = workerQueue; }
    public int getPdfMinTextCharacters() { return pdfMinTextCharacters; }
    public void setPdfMinTextCharacters(int pdfMinTextCharacters) { this.pdfMinTextCharacters = pdfMinTextCharacters; }
    public String getTesseractCommand() { return tesseractCommand; }
    public void setTesseractCommand(String tesseractCommand) { this.tesseractCommand = tesseractCommand; }
    public String getTesseractLanguage() { return tesseractLanguage; }
    public void setTesseractLanguage(String tesseractLanguage) { this.tesseractLanguage = tesseractLanguage; }
    public int getOcrDpi() { return ocrDpi; }
    public void setOcrDpi(int ocrDpi) { this.ocrDpi = ocrDpi; }
    public long getOcrTimeoutSeconds() { return ocrTimeoutSeconds; }
    public void setOcrTimeoutSeconds(long ocrTimeoutSeconds) { this.ocrTimeoutSeconds = ocrTimeoutSeconds; }
    public Path getStorageDir() { return storageDir; }
    public void setStorageDir(Path storageDir) { this.storageDir = storageDir; }
}
