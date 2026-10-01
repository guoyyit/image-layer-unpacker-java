package com.github.guoyyit.imagelayerunpackerjava.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.github.guoyyit.imagelayerunpackerjava.layer.LayerManager;
import com.github.guoyyit.imagelayerunpackerjava.storage.StorageLayout;

@Component
@Order(0)
public class RecoveryService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final StorageLayout layout;
    private final LayerManager layerManager;

    public RecoveryService(StorageLayout layout, LayerManager layerManager) {
        this.layout = layout;
        this.layerManager = layerManager;
    }

    @Override
    public void run(String... args) {
        log.info("initializing storage and recovering layer state");
        layerManager.initialize();
        layerManager.recover();
        log.info("recovery complete; {} committed layer(s) available",
                layerManager.list().size());
    }
}
