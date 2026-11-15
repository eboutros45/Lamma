package com.lamma.ui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.Map;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

/** Draws QR codes (ZXing) as JavaFX images: always black on white, so they scan in any theme. */
final class QrCodes {

    private QrCodes() {
    }

    /** The bit pattern for the text (a 1-module quiet zone, medium error correction). */
    static BitMatrix matrix(String text, int size) throws WriterException {
        return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, Map.of(
                EncodeHintType.MARGIN, 1,
                EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET, "UTF-8"));
    }

    static Image image(String text, int size) throws WriterException {
        BitMatrix bits = matrix(text, size);
        WritableImage image = new WritableImage(bits.getWidth(), bits.getHeight());
        PixelWriter pixels = image.getPixelWriter();
        for (int y = 0; y < bits.getHeight(); y++) {
            for (int x = 0; x < bits.getWidth(); x++) {
                pixels.setColor(x, y, bits.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return image;
    }
}
