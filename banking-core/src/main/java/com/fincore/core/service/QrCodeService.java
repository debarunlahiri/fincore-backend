package com.fincore.core.service;

import com.fincore.common.exception.FincoreException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Renders text as a QR code in SVG: sharp at any size, prints well, no image handling needed. */
@Service
public class QrCodeService {

    private static final int QUIET_ZONE = 2;

    /** A square SVG, one black path of module rectangles on a white background. */
    public String svg(String content) {
        BitMatrix matrix;
        try {
            matrix =
                    new QRCodeWriter()
                            .encode(
                                    content,
                                    BarcodeFormat.QR_CODE,
                                    0,
                                    0,
                                    Map.of(
                                            EncodeHintType.ERROR_CORRECTION,
                                            ErrorCorrectionLevel.M,
                                            EncodeHintType.MARGIN,
                                            0,
                                            EncodeHintType.CHARACTER_SET,
                                            "UTF-8"));
        } catch (WriterException e) {
            throw FincoreException.badRequest("QR_TOO_LONG", "Too much text for a QR code");
        }
        int size = matrix.getWidth() + 2 * QUIET_ZONE;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y)) {
                    path.append('M')
                            .append(x + QUIET_ZONE)
                            .append(' ')
                            .append(y + QUIET_ZONE)
                            .append("h1v1h-1z");
                }
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 "
                + size
                + " "
                + size
                + "\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>"
                + "<path d=\""
                + path
                + "\" fill=\"#000\"/></svg>";
    }
}
