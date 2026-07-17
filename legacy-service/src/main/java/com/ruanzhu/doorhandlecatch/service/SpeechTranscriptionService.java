package com.ruanzhu.doorhandlecatch.service;

import org.springframework.web.multipart.MultipartFile;

public interface SpeechTranscriptionService {

    String transcribe(MultipartFile file);
}
