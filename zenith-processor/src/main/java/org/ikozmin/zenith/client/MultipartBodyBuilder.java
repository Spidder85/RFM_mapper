package org.ikozmin.zenith.client;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class MultipartBodyBuilder {
    private final String boundary;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private MultipartBodyBuilder(String boundary) {
        this.boundary = boundary;
    }

    public static MultipartBodyBuilder create(String boundary) {
        return new MultipartBodyBuilder(boundary);
    }

    public MultipartBodyBuilder part(String name, String contentType, byte[] value) {
        try {
            output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(value);
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
            return this;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build multipart body", e);
        }
    }

    public byte[] build() {
        try {
            output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to finish multipart body", e);
        }
    }
}
