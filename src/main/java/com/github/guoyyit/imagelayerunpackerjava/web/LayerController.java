package com.github.guoyyit.imagelayerunpackerjava.web;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.guoyyit.imagelayerunpackerjava.layer.LayerManager;
import com.github.guoyyit.imagelayerunpackerjava.model.LayerRecord;

@RestController
@RequestMapping("/api/layers")
public class LayerController {

    private final LayerManager layerManager;

    public LayerController(LayerManager layerManager) {
        this.layerManager = layerManager;
    }

    @PostMapping(path = "/{id}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public LayerRecord upload(@PathVariable String id, @RequestBody byte[] body) {
        return layerManager.submit(id, new ByteArrayInputStream(body));
    }

    @GetMapping
    public List<LayerRecord> list() {
        return layerManager.list();
    }

    @GetMapping("/{id}")
    public LayerRecord get(@PathVariable String id) {
        return layerManager.get(id);
    }

    @DeleteMapping("/{id}")
    public org.springframework.http.ResponseEntity<Void> delete(@PathVariable String id) {
        layerManager.delete(id);
        return org.springframework.http.ResponseEntity.noContent().build();
    }
}
