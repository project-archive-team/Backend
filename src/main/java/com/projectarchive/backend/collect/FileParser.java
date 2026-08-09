package com.projectarchive.backend.collect;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 파일에서 평문을 뽑는다. 업로드와 Google Drive 수집이 같은 경로를 쓴다 —
 * 같은 pdf를 두 군데서 다르게 읽을 이유가 없다.
 */
@Component
public class FileParser {

    /** 그대로 읽으면 되는 텍스트·코드 확장자. GitHub 수집기도 이 목록을 쓴다. */
    static final Set<String> TEXT_EXT = Set.of(
            "java", "kt", "py", "js", "jsx", "ts", "tsx", "go", "rs", "rb", "c", "h", "cpp", "cs",
            "sql", "sh", "yml", "yaml", "json", "toml", "gradle", "xml", "md", "txt", "csv",
            "ipynb", "swift", "php", "scala", "r", "m", "vue", "svelte", "properties", "env", "dockerfile");

    public String parse(MultipartFile file) throws IOException {
        return parse(file.getOriginalFilename(), file.getBytes());
    }

    public String parse(String filename, byte[] bytes) throws IOException {
        String name = filename == null ? "" : filename.toLowerCase();
        if (name.endsWith(".pdf")) {
            return pdf(bytes);
        }
        if (name.endsWith(".pptx")) {
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                return pptx(in);
            }
        }
        if (isTextExtension(name)) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            // 확장자만 텍스트인 바이너리를 걸러낸다 — 임베딩에 쓰레기가 들어가면 검색이 망가진다.
            return text.indexOf('\0') >= 0 ? "" : text;
        }
        throw new IllegalArgumentException("지원하지 않는 파일 형식입니다: " + filename);
    }

    public boolean supports(String filename) {
        String n = filename == null ? "" : filename.toLowerCase();
        return n.endsWith(".pdf") || n.endsWith(".pptx") || isTextExtension(n);
    }

    static boolean isTextExtension(String lowercaseName) {
        int dot = lowercaseName.lastIndexOf('.');
        if (dot < 0) {
            // Dockerfile, Makefile처럼 확장자가 없는 것들.
            return TEXT_EXT.contains(lowercaseName);
        }
        return TEXT_EXT.contains(lowercaseName.substring(dot + 1));
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
