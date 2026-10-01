package com.github.guoyyit.imagelayerunpackerjava.web;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.guoyyit.imagelayerunpackerjava.merge.MergeService;

@RestController
@RequestMapping("/api/view")
public class MergeController {

    private final MergeService mergeService;

    public MergeController(MergeService mergeService) {
        this.mergeService = mergeService;
    }

    @GetMapping("/list")
    public List<MergeService.MergeNode> list(@RequestParam(defaultValue = "/") String path)
            throws IOException {
        return mergeService.listDirectory(path);
    }

    @GetMapping("/read")
    public ResponseEntity<byte[]> read(@RequestParam String path) throws IOException {
        byte[] content = mergeService.readFile(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .body(content);
    }

    @GetMapping("/stat")
    public MergeService.MergeNode stat(@RequestParam String path) throws IOException {
        return mergeService.stat(path);
    }
}
