package com.portfolio.clipcurator.upload;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/upload")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/init")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadInitResponse initUpload(@RequestBody UploadInitRequest request) {
        return uploadService.initUpload(request);
    }

    @PostMapping("/confirm/{id}")
    public UploadConfirmResponse confirmUpload(@PathVariable("id") UUID id) {
        return uploadService.confirmUpload(id);
    }
}
