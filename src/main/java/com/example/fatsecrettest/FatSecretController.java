package com.example.fatsecrettest;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;


@RestController
public class FatSecretController {

    private static final Logger log =
            LoggerFactory.getLogger(FatSecretController.class);

    private final String clientId;
    private final String clientSecret;

    private final RestClient restClient;
    private final Path uploadDirectory;


    public FatSecretController(
            @Value("${fatsecret.client-id}")
            String clientId,

            @Value("${fatsecret.client-secret}")
            String clientSecret,

            @Value("${app.upload-directory:/home/ady/Spring/uploads}")
            String uploadDirectory
    ) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;

        this.restClient = RestClient.create();

        this.uploadDirectory = Path.of(uploadDirectory)
                .toAbsolutePath()
                .normalize();

        try {
            Files.createDirectories(this.uploadDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not create upload directory: "
                            + this.uploadDirectory,
                    exception
            );
        }
    }


    /*
     * Receives an image, saves it, sends it to the local classifier,
     * searches FatSecret and returns the combined result.
     *
     * The INFO log messages are written live to the Spring console. most of this can be ignored it was used for debugging
     */
    @PostMapping(
            value = "/recognize",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Map<String, Object> recognize(
            @RequestPart("image")
            MultipartFile image,

            /*
             * This is only a test identity supplied by the client.
             * Connecting this to supabase to crosscheck would be a huge headache thus no security measures 8)
             */
            @RequestHeader(
                    value = "X-User-Id",
                    required = false
            )
            String uploaderId,

            HttpServletRequest request
    ) throws Exception {

        String requestId =
                UUID.randomUUID().toString();

        long totalStart =
                System.nanoTime();

        validateImage(image);

        String resolvedUploaderId =
                uploaderId == null || uploaderId.isBlank()
                        ? "anonymous"
                        : uploaderId;

        String clientIp =
                request.getRemoteAddr();

        String userAgent =
                request.getHeader("User-Agent");

        log.info(
                "[{}] Upload received: user={}, ip={}, filename={}, contentType={}, sizeBytes={}",
                requestId,
                resolvedUploaderId,
                clientIp,
                image.getOriginalFilename(),
                image.getContentType(),
                image.getSize()
        );

        Path savedImagePath =
                saveImage(image);

        String storedFilename =
                savedImagePath.getFileName().toString();

        String uploadId =
                storedFilename.substring(
                        0,
                        storedFilename.lastIndexOf('.')
                );

        log.info(
                "[{}] Image saved: uploadId={}, path={}",
                requestId,
                uploadId,
                savedImagePath
        );

        Properties metadata =
                new Properties();

        metadata.setProperty(
                "request.id",
                requestId
        );

        metadata.setProperty(
                "upload.id",
                uploadId
        );

        metadata.setProperty(
                "upload.timestamp",
                Instant.now().toString()
        );

        metadata.setProperty(
                "uploader.id",
                resolvedUploaderId
        );

        metadata.setProperty(
                "uploader.ip",
                safeValue(clientIp)
        );

        metadata.setProperty(
                "uploader.userAgent",
                safeValue(userAgent)
        );

        metadata.setProperty(
                "image.originalFilename",
                safeValue(image.getOriginalFilename())
        );

        metadata.setProperty(
                "image.storedFilename",
                storedFilename
        );

        metadata.setProperty(
                "image.contentType",
                safeValue(image.getContentType())
        );

        metadata.setProperty(
                "image.sizeBytes",
                Long.toString(image.getSize())
        );

        try {
            log.info(
                    "[{}] Sending image to Python classifier",
                    requestId
            );

            long classifierStart =
                    System.nanoTime();

            ClassifierResponse classification =
                    classifyImage(image);

            double classifierRoundTripMs =
                    elapsedMilliseconds(classifierStart);

            if (classification == null
                    || classification.label == null
                    || classification.label.isBlank()) {

                throw new ResponseStatusException(
                        INTERNAL_SERVER_ERROR,
                        "The classifier did not return a label."
                );
            }

            log.info(
                    "[{}] Classification finished: label={}, confidence={}, modelInferenceMs={}, roundTripMs={}",
                    requestId,
                    classification.label,
                    classification.confidence,
                    classification.modelInferenceMs,
                    classifierRoundTripMs
            );

            log.info(
                    "[{}] Searching FatSecret for label='{}'",
                    requestId,
                    classification.label
            );

            long fatSecretStart =
                    System.nanoTime();

            Map<String, Object> fatSecretResponse =
                    searchFatSecret(
                            classification.label
                    );

            double fatSecretMs =
                    elapsedMilliseconds(fatSecretStart);

            int fatSecretResultCount =
                    countFatSecretFoods(
                            fatSecretResponse
                    );

            log.info(
                    "[{}] FatSecret request finished: resultCount={}, durationMs={}",
                    requestId,
                    fatSecretResultCount,
                    fatSecretMs
            );

            double totalMs =
                    elapsedMilliseconds(totalStart);

            addClassificationMetadata(
                    metadata,
                    classification
            );

            metadata.setProperty(
                    "fatsecret.resultCount",
                    Integer.toString(
                            fatSecretResultCount
                    )
            );

            metadata.setProperty(
                    "timing.classifierRoundTripMs",
                    Double.toString(
                            classifierRoundTripMs
                    )
            );

            metadata.setProperty(
                    "timing.fatSecretMs",
                    Double.toString(
                            fatSecretMs
                    )
            );

            metadata.setProperty(
                    "timing.totalMs",
                    Double.toString(
                            totalMs
                    )
            );

            metadata.setProperty(
                    "request.success",
                    "true"
            );

            Path metadataPath =
                    saveMetadata(
                            uploadId,
                            metadata
                    );

            Map<String, Object> uploadInformation =
                    new LinkedHashMap<>();

            uploadInformation.put(
                    "id",
                    uploadId
            );

            uploadInformation.put(
                    "uploaderId",
                    resolvedUploaderId
            );

            uploadInformation.put(
                    "clientIp",
                    clientIp
            );

            uploadInformation.put(
                    "userAgent",
                    userAgent
            );

            uploadInformation.put(
                    "originalFilename",
                    image.getOriginalFilename()
            );

            uploadInformation.put(
                    "storedFilename",
                    storedFilename
            );

            uploadInformation.put(
                    "metadataFilename",
                    metadataPath
                            .getFileName()
                            .toString()
            );

            uploadInformation.put(
                    "sizeBytes",
                    image.getSize()
            );

            uploadInformation.put(
                    "contentType",
                    image.getContentType()
            );

            uploadInformation.put(
                    "width",
                    classification.width
            );

            uploadInformation.put(
                    "height",
                    classification.height
            );

            uploadInformation.put(
                    "format",
                    classification.imageFormat
            );

            Map<String, Object> classifierInformation =
                    new LinkedHashMap<>();

            classifierInformation.put(
                    "label",
                    classification.label
            );

            classifierInformation.put(
                    "confidence",
                    classification.confidence
            );

            classifierInformation.put(
                    "topPredictions",
                    convertPredictions(
                            classification.topPredictions
                    )
            );

            Map<String, Object> timingInformation =
                    new LinkedHashMap<>();

            timingInformation.put(
                    "modelInferenceMs",
                    classification.modelInferenceMs
            );

            timingInformation.put(
                    "classifierRoundTripMs",
                    classifierRoundTripMs
            );

            timingInformation.put(
                    "fatSecretMs",
                    fatSecretMs
            );

            timingInformation.put(
                    "totalMs",
                    totalMs
            );

            Map<String, Object> result =
                    new LinkedHashMap<>();

            result.put(
                    "requestId",
                    requestId
            );

            result.put(
                    "upload",
                    uploadInformation
            );

            result.put(
                    "classifier",
                    classifierInformation
            );

            result.put(
                    "timing",
                    timingInformation
            );

            result.put(
                    "fatSecret",
                    fatSecretResponse
            );

            log.info(
                    "[{}] Complete request finished successfully in {} ms",
                    requestId,
                    totalMs
            );

            return result;

        } catch (Exception exception) {
            double failedAfterMs =
                    elapsedMilliseconds(totalStart);

            metadata.setProperty(
                    "request.success",
                    "false"
            );

            metadata.setProperty(
                    "request.errorType",
                    exception
                            .getClass()
                            .getName()
            );

            metadata.setProperty(
                    "request.errorMessage",
                    safeValue(
                            exception.getMessage()
                    )
            );

            metadata.setProperty(
                    "timing.totalMs",
                    Double.toString(
                            failedAfterMs
                    )
            );

            /*
             * Metadata for debugging mostly
             */
            try {
                saveMetadata(
                        uploadId,
                        metadata
                );
            } catch (Exception metadataException) {
                log.warn(
                        "[{}] Failure metadata could not be saved",
                        requestId,
                        metadataException
                );
            }

            log.error(
                    "[{}] Recognition request failed after {} ms",
                    requestId,
                    failedAfterMs,
                    exception
            );

            throw exception;
        }
    }


    /*
     * Manual FatSecret test:
     *
     * GET /search?label=pizza
     */
    @GetMapping(
            value = "/search",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Map<String, Object> search(
            @RequestParam String label
    ) {
        log.info(
                "Manual FatSecret search requested for label='{}'",
                label
        );

        return searchFatSecret(label);
    }


    private void validateImage(
            MultipartFile image
    ) {
        if (image.isEmpty()) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "The uploaded image is empty."
            );
        }

        String contentType =
                image.getContentType();

        if (contentType == null
                || !contentType.startsWith("image/")) {

            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "The uploaded file must be an image."
            );
        }
    }


    private Path saveImage(
            MultipartFile image
    ) {
        String extension =
                determineFileExtension(image);

        String filename =
                UUID.randomUUID() + extension;

        Path destination =
                uploadDirectory
                        .resolve(filename)
                        .normalize();

        if (!destination.startsWith(uploadDirectory)) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "Invalid upload path."
            );
        }

        try {
            Files.copy(
                    image.getInputStream(),
                    destination,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    INTERNAL_SERVER_ERROR,
                    "The image could not be saved.",
                    exception
            );
        }

        return destination;
    }


    private Path saveMetadata(
            String uploadId,
            Properties metadata
    ) {
        Path metadataPath =
                uploadDirectory
                        .resolve(
                                uploadId
                                        + ".properties"
                        )
                        .normalize();

        if (!metadataPath.startsWith(uploadDirectory)) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "Invalid metadata path."
            );
        }

        try (
                OutputStream outputStream =
                        Files.newOutputStream(
                                metadataPath
                        )
        ) {
            metadata.store(
                    outputStream,
                    "Food recognition upload metadata"
            );

        } catch (IOException exception) {
            throw new ResponseStatusException(
                    INTERNAL_SERVER_ERROR,
                    "The upload metadata could not be saved.",
                    exception
            );
        }

        return metadataPath;
    }


    private void addClassificationMetadata(
            Properties metadata,
            ClassifierResponse classification
    ) {
        metadata.setProperty(
                "image.width",
                Integer.toString(
                        classification.width
                )
        );

        metadata.setProperty(
                "image.height",
                Integer.toString(
                        classification.height
                )
        );

        metadata.setProperty(
                "image.format",
                safeValue(
                        classification.imageFormat
                )
        );

        metadata.setProperty(
                "classifier.label",
                safeValue(
                        classification.label
                )
        );

        metadata.setProperty(
                "classifier.confidence",
                Double.toString(
                        classification.confidence
                )
        );

        metadata.setProperty(
                "timing.modelInferenceMs",
                Double.toString(
                        classification.modelInferenceMs
                )
        );

        if (classification.topPredictions != null) {
            for (
                    int index = 0;
                    index
                            < classification
                            .topPredictions
                            .size();
                    index++
            ) {
                Prediction prediction =
                        classification
                                .topPredictions
                                .get(index);

                metadata.setProperty(
                        "classifier.prediction."
                                + index
                                + ".label",
                        safeValue(
                                prediction.label
                        )
                );

                metadata.setProperty(
                        "classifier.prediction."
                                + index
                                + ".confidence",
                        Double.toString(
                                prediction.confidence
                        )
                );
            }
        }
    }


    private List<Map<String, Object>> convertPredictions(
            List<Prediction> predictions
    ) {
        List<Map<String, Object>> result =
                new ArrayList<>();

        if (predictions == null) {
            return result;
        }

        for (Prediction prediction : predictions) {
            Map<String, Object> predictionMap =
                    new LinkedHashMap<>();

            predictionMap.put(
                    "label",
                    prediction.label
            );

            predictionMap.put(
                    "confidence",
                    prediction.confidence
            );

            result.add(
                    predictionMap
            );
        }

        return result;
    }


    private ClassifierResponse classifyImage(
            MultipartFile image
    ) throws IOException {

        String filename =
                image.getOriginalFilename();

        if (filename == null || filename.isBlank()) {
            filename =
                    "uploaded-image.jpg";
        }

        String finalFilename =
                filename;

        ByteArrayResource imageResource =
                new ByteArrayResource(
                        image.getBytes()
                ) {

                    @Override
                    public String getFilename() {
                        return finalFilename;
                    }
                };

        MediaType imageContentType =
                MediaType
                        .APPLICATION_OCTET_STREAM;

        if (image.getContentType() != null) {
            imageContentType =
                    MediaType.parseMediaType(
                            image.getContentType()
                    );
        }

        MultipartBodyBuilder multipartBody =
                new MultipartBodyBuilder();

        multipartBody
                .part(
                        "image",
                        imageResource
                )
                .contentType(
                        imageContentType
                );

        return restClient
                .post()
                .uri(
                        "http://127.0.0.1:8000/classify"
                )
                .contentType(
                        MediaType.MULTIPART_FORM_DATA
                )
                .body(
                        multipartBody.build()
                )
                .retrieve()
                .body(
                        ClassifierResponse.class
                );
    }

    //most of this is taken from the fatsecret website, with a bit of an extension
    @SuppressWarnings("unchecked")
    private Map<String, Object> searchFatSecret(
            String label
    ) {
        String token =
                getToken();

        Map<String, Object> response =
                restClient
                        .get()
                        .uri(
                                uriBuilder ->
                                        uriBuilder
                                                .scheme("https")
                                                .host(
                                                        "platform.fatsecret.com"
                                                )
                                                .path(
                                                        "/rest/foods/search/v1"
                                                )
                                                .queryParam(
                                                        "search_expression",
                                                        label
                                                )
                                                .queryParam(
                                                        "format",
                                                        "json"
                                                )
                                                .queryParam(
                                                        "max_results",
                                                        10
                                                )
                                                .build()
                        )
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + token
                        )
                        .retrieve()
                        .body(
                                Map.class
                        );

        if (response == null) {
            throw new ResponseStatusException(
                    INTERNAL_SERVER_ERROR,
                    "FatSecret returned an empty response."
            );
        }

        return response;
    }


    @SuppressWarnings("unchecked")
    private String getToken() {
        String credentials =
                clientId + ":" + clientSecret;

        String encodedCredentials =
                Base64
                        .getEncoder()
                        .encodeToString(
                                credentials.getBytes(
                                        StandardCharsets.UTF_8
                                )
                        );

        LinkedMultiValueMap<String, String> form =
                new LinkedMultiValueMap<>();

        form.add(
                "grant_type",
                "client_credentials"
        );

        form.add(
                "scope",
                "basic"
        );

        Map<String, Object> response =
                restClient
                        .post()
                        .uri(
                                "https://oauth.fatsecret.com/connect/token"
                        )
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Basic "
                                        + encodedCredentials
                        )
                        .contentType(
                                MediaType
                                        .APPLICATION_FORM_URLENCODED
                        )
                        .body(form)
                        .retrieve()
                        .body(
                                Map.class
                        );

        if (response == null) {
            throw new ResponseStatusException(
                    INTERNAL_SERVER_ERROR,
                    "FatSecret returned no token response."
            );
        }

        Object accessToken =
                response.get(
                        "access_token"
                );

        if (accessToken == null) {
            throw new ResponseStatusException(
                    INTERNAL_SERVER_ERROR,
                    "FatSecret did not return an access token."
            );
        }

        return accessToken.toString();
    }


    @SuppressWarnings("unchecked")
    private int countFatSecretFoods(
            Map<String, Object> response
    ) {
        Object foodsValue =
                response.get("foods");

        if (!(foodsValue instanceof Map)) {
            return 0;
        }

        Map<String, Object> foods =
                (Map<String, Object>) foodsValue;

        Object foodValue =
                foods.get("food");

        if (foodValue instanceof List) {
            return ((List<?>) foodValue).size();
        }

        if (foodValue instanceof Map) {
            return 1;
        }

        return 0;
    }


    private String determineFileExtension(
            MultipartFile image
    ) {
        String contentType =
                image.getContentType();

        if ("image/png".equals(contentType)) {
            return ".png";
        }

        if ("image/webp".equals(contentType)) {
            return ".webp";
        }

        if ("image/gif".equals(contentType)) {
            return ".gif";
        }

        return ".jpg";
    }


    private double elapsedMilliseconds(
            long startingNanoTime
    ) {
        return (
                System.nanoTime()
                        - startingNanoTime
        ) / 1_000_000.0;
    }


    private String safeValue(
            String value
    ) {
        return value == null
                ? ""
                : value;
    }


    public static class ClassifierResponse {

        public String label;

        public double confidence;

        public double modelInferenceMs;

        public int width;

        public int height;

        public String imageFormat;

        public List<Prediction> topPredictions;
    }


    public static class Prediction {

        public String label;

        public double confidence;
    }
}
