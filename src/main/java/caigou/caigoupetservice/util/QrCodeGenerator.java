package caigou.caigoupetservice.util;

import caigou.caigoupetservice.exception.ApiException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/**
 * 二维码生成工具:内容字符串 → PNG base64 dataURL
 * 复刻 Express 端参数:宽 220px、margin 1、黑 #000 白 #fff
 */
public final class QrCodeGenerator {

    private QrCodeGenerator() {
    }

    /**
     * 将内容编码为二维码 PNG dataURL
     * @param content 二维码承载内容(如 JSON 字符串)
     * @return "data:image/png;base64,..." 格式的数据
     * @throws ApiException 生成失败时抛出(500)
     */
    public static String toDataUrl(String content) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    content, BarcodeFormat.QR_CODE, 220, 220,
                    Map.of(EncodeHintType.MARGIN, 1, EncodeHintType.CHARACTER_SET, "UTF-8"));
            BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
            // 逐像素绘制二维码矩阵:true=黑色,false=白色
            for (int x = 0; x < matrix.getWidth(); x++) {
                for (int y = 0; y < matrix.getHeight(); y++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (WriterException | IOException e) {
            throw new ApiException(500, "QR code generation failed");
        }
    }
}
