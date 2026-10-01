package com.github.guoyyit.imagelayerunpackerjava.web;

import com.github.guoyyit.imagelayerunpackerjava.layer.LayerManager;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerMeta;
import com.github.guoyyit.imagelayerunpackerjava.layer.UnpackException;
import com.github.guoyyit.imagelayerunpackerjava.view.MergedView;
import com.github.guoyyit.imagelayerunpackerjava.view.ViewEntry;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * HTTP API: layer unpack/delete/list plus merged-view reads.
 */
@RestController
@RequestMapping("/api")
public class LayerController {

    private final LayerManager layerManager;

    public LayerController(LayerManager layerManager) {
        this.layerManager = layerManager;
    }

    /** Upload and unpack a layer tar (idempotent per layer id). */
    @PostMapping(value = "/layers/{id}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public LayerMeta unpack(@PathVariable String id, InputStream body) throws IOException, UnpackException {
        return layerManager.unpackLayer(id, body);
    }

    /** List all layers in stacking order. */
    @GetMapping("/layers")
    public List<LayerMeta> listLayers() {
        return layerManager.listLayers();
    }

    /** Delete a layer, reclaiming its disk space. */
    @DeleteMapping("/layers/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        layerManager.deleteLayer(id);
        return ResponseEntity.noContent().build();
    }

    /** List a directory of the merged view. */
    @GetMapping("/view/tree")
    public List<ViewEntry> tree(@RequestParam(defaultValue = "/") String path) throws IOException {
        try (MergedView view = layerManager.openView()) {
            return view.list(path);
        }
    }

    /** Download a file from the merged view. */
    @GetMapping("/view/file")
    public ResponseEntity<InputStreamResource> file(@RequestParam String path) throws IOException {
        MergedView view = layerManager.openView();
        InputStream in;
        try {
            in = view.openFile(path);
        } catch (IOException | RuntimeException e) {
            view.close();
            throw e;
        }
        // Keep the pinned view alive until the response stream is consumed.
        InputStream releasing = new FilterInputStream(in) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    view.close();
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(releasing));
    }
}
