package com.projectarchive.backend.collect;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 업로드된 PPT/PDF/MD에서 평문을 뽑는다. */
@Component
public class FileParser {

    public String parse(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try (InputStream in = file.getInputStream()) {
            if (name.endsWith(".pdf")) {
                return pdf(file.getBytes());
            }
            if (name.endsWith(".pptx")) {
                return pptx(in);
            }
            if (name.endsWith(".md") || name.endsWith(".txt")) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("unsupported file type: " + name + " (pdf, pptx, md, txt만 지원)");
    }

    public boolean supports(String filename) {
        String n = filename == null ? "" : filename.toLowerCase();
        return n.endsWith(".pdf") || n.endsWith(".pptx") || n.endsWith(".md") || n.endsWith(".txt");
    }

    private String pdf(byte[] bytes) throws IOException {
        try (var doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String pptx(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XMLSlideShow ppt = new XMLSlideShow(in)) {
            int n = 1;
            for (XSLFSlide slide : ppt.getSlides()) {
                sb.append("## slide ").append(n++).append('\n');
                for (var shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape text) {
                        sb.append(text.getText()).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }
}
