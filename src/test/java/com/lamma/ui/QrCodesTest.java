package com.lamma.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

/** The generated QR decodes back to the invitation text (no display needed). */
class QrCodesTest {

    @Test
    void invitationPayloadRoundTrips() throws Exception {
        String payload = "lamma://join/AbCdEfGhIjKlMnOpQrStUv_-";
        BitMatrix bits = QrCodes.matrix(payload, 220);
        int w = bits.getWidth();
        int h = bits.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y * w + x] = bits.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        String decoded = new QRCodeReader().decode(
                new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, pixels)))).getText();
        assertEquals(payload, decoded);
    }
}
