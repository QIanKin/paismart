package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.service.agent.AgentAssetService;
import com.yizhaoqi.smartpai.service.agent.AgentUserResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/agent/assets")
public class AgentAssetController {

    private final AgentAssetService assetService;
    private final AgentUserResolver userResolver;

    public AgentAssetController(AgentAssetService assetService, AgentUserResolver userResolver) {
        this.assetService = assetService;
        this.userResolver = userResolver;
    }

    @PostMapping("/images")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file,
                                         @RequestAttribute("userId") String userId) {
        User user = userResolver.resolve(userId);
        AgentAssetService.UploadedImage uploaded = assetService.uploadChatImage(
                file,
                String.valueOf(user.getId()),
                user.getPrimaryOrg()
        );
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "ok",
                "data", uploaded
        ));
    }

    @PostMapping("/attachments")
    public ResponseEntity<?> uploadAttachment(@RequestParam("file") MultipartFile file,
                                              @RequestAttribute("userId") String userId) {
        User user = userResolver.resolve(userId);
        AgentAssetService.UploadedAttachment uploaded = assetService.uploadChatAttachment(
                file,
                String.valueOf(user.getId()),
                user.getPrimaryOrg()
        );
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "ok",
                "data", uploaded
        ));
    }
}
