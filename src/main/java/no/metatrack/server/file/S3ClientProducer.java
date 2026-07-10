package no.metatrack.server.file;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@ApplicationScoped
public class S3ClientProducer {
    @ConfigProperty(name = "metatrack.s3.endpoint")
    URI endpoint;

    @ConfigProperty(name = "metatrack.s3.region")
    String region;

    @ConfigProperty(name = "metatrack.s3.access-key")
    String accessKey;

    @ConfigProperty(name = "metatrack.s3.secret-key")
    String secretKey;

    @ConfigProperty(name = "metatrack.s3.path-style-access")
    boolean pathStyleAccess;

    @Produces
    @ApplicationScoped
    S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider())
                .serviceConfiguration(serviceConfiguration())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    @Produces
    @ApplicationScoped
    S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider())
                .serviceConfiguration(serviceConfiguration())
                .build();
    }

    private StaticCredentialsProvider credentialsProvider() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }

    private S3Configuration serviceConfiguration() {
        return S3Configuration.builder().pathStyleAccessEnabled(pathStyleAccess).build();
    }
}