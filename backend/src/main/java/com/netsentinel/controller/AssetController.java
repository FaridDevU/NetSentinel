package com.netsentinel.controller;

import com.netsentinel.dto.AssetDto;
import com.netsentinel.service.AssetService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @GetMapping("/assets")
    public ResponseEntity<List<AssetDto>> getAssets() {
        return ResponseEntity.ok(assetService.getAssets());
    }
}
