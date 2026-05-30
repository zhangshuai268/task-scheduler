package com.example.worker.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

@Slf4j
@Service
public class S3Service {

    @Value("${s3.endpoint:}")
    private String endpoint;
    @Value("${s3.access-key:}")
    private String accessKey;
    @Value("${s3.secret-key:}")
    private String secretKey;
    @Value("${s3.region:us-east-1}")
    private String region;

    private S3Client s3Client;

    @PostConstruct
    public void init() {
        if (endpoint == null || endpoint.isBlank()) {
            log.warn("S3 not configured, operations will be skipped");
            return;
        }
        s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true).build();
    }

    public void download(String s3Path, String localPath) {
        if (s3Client == null) return;
        try {
            String[] parsed = parseS3Path(s3Path);
            Path localDir = Path.of(localPath);
            Files.createDirectories(localDir);
            if (parsed[1].endsWith("/") || parsed[1].isEmpty()) {
                downloadDir(parsed[0], parsed[1], localDir);
            } else {
                downloadFile(parsed[0], parsed[1], localDir.resolve(Path.of(parsed[1]).getFileName()));
            }
        } catch (Exception e) {
            log.error("S3 download failed: {} → {}: {}", s3Path, localPath, e.getMessage());
        }
    }

    public void upload(String localPath, String s3Path) {
        if (s3Client == null) return;
        try {
            String[] parsed = parseS3Path(s3Path);
            Path local = Path.of(localPath);
            if (!Files.exists(local)) return;
            if (Files.isDirectory(local)) {
                uploadDir(local, parsed[0], parsed[1]);
            } else {
                uploadFile(local, parsed[0], parsed[1]);
            }
        } catch (Exception e) {
            log.error("S3 upload failed: {} → {}: {}", localPath, s3Path, e.getMessage());
        }
    }

    private void downloadDir(String bucket, String prefix, Path localDir) throws IOException {
        ListObjectsV2Request req = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build();
        String token = null;
        ListObjectsV2Response resp;
        do {
            if (token != null) req = req.toBuilder().continuationToken(token).build();
            resp = s3Client.listObjectsV2(req);
            for (S3Object obj : resp.contents()) {
                if (obj.key().endsWith("/")) continue;
                String rel = obj.key().substring(prefix.length());
                if (rel.startsWith("/")) rel = rel.substring(1);
                downloadFile(bucket, obj.key(), localDir.resolve(rel));
            }
            token = resp.nextContinuationToken();
        } while (resp.isTruncated());
    }

    private void downloadFile(String bucket, String key, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), target);
    }

    private void uploadDir(Path localDir, String bucket, String prefix) throws IOException {
        Files.walkFileTree(localDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                uploadFile(file, bucket, prefix + localDir.relativize(file).toString().replace("\\", "/"));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void uploadFile(Path file, String bucket, String key) {
        try {
            s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromFile(file));
        } catch (Exception e) {
            log.error("Upload failed: {} → s3://{}/{}: {}", file, bucket, key, e.getMessage());
        }
    }

    private String[] parseS3Path(String s3Path) {
        String path = s3Path.replaceFirst("^s3://", "");
        int idx = path.indexOf('/');
        return idx < 0 ? new String[]{path, ""} : new String[]{path.substring(0, idx), path.substring(idx + 1)};
    }
}
