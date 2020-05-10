package com.daxiang.controller;

import com.daxiang.model.Response;
import com.daxiang.service.MobileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Created by jiangyitao.
 */
@RestController
@RequestMapping("/mobile")
public class MobileController {

    @Autowired
    private MobileService mobileService;

    @PostMapping("/{mobileId}/installApp")
    public Response installApp(MultipartFile app, @PathVariable String mobileId) {
        return mobileService.installApp(app, mobileId);
    }

    @GetMapping("/{mobileId}")
    public Response getMobile(@PathVariable String mobileId) {
        return mobileService.getMobile(mobileId);
    }
}
