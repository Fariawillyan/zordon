/* Copyright 2026 Willyan Faria — Apache License 2.0 */
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.imageio.ImageIO;

/** Reproduz a marca geométrica de VoiceVisualizer; execute da raiz com java packaging/windows/GenerateIcon.java. */
class GenerateIcon {
    public static void main(String[] args) throws Exception {
        Path output = Path.of("zordon-desktop/src/main/resources/zordon/desktop/icons");
        Files.createDirectories(output);
        int[] sizes = {16, 32, 48, 64, 128, 256};
        var images = new ArrayList<byte[]>();
        for (int size : sizes) {
            // Supersampling preserva o traço nas dimensões pequenas da barra de tarefas.
            BufferedImage large = new BufferedImage(size * 4, size * 4, BufferedImage.TYPE_INT_ARGB);
            var g = large.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(size * 4 / 112.0, size * 4 / 112.0);
            g.setColor(new Color(0x081821));
            g.fillRoundRect(0, 0, 112, 112, 24, 24);
            g.setColor(new Color(0x12E3F7));
            g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER));
            Path2D outer = new Path2D.Double();
            outer.moveTo(56, 18);
            outer.lineTo(14, 90);
            outer.lineTo(98, 90);
            outer.closePath();
            g.draw(outer);
            g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER));
            Path2D inner = new Path2D.Double();
            inner.moveTo(56, 44);
            inner.lineTo(36, 78);
            inner.lineTo(76, 78);
            inner.lineTo(60, 51);
            g.draw(inner);
            g.dispose();
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            var scaled = image.createGraphics();
            scaled.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            scaled.drawImage(large, 0, 0, size, size, null);
            scaled.dispose();
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            images.add(bytes.toByteArray());
            Files.write(output.resolve("zordon-" + size + ".png"), bytes.toByteArray());
        }
        int offset = 6 + sizes.length * 16;
        ByteBuffer ico = ByteBuffer.allocate(offset + images.stream().mapToInt(a -> a.length).sum())
                .order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        for (int i = 0; i < sizes.length; i++) {
            ico.put((byte) sizes[i]).put((byte) sizes[i]).put((byte) 0).put((byte) 0);
            ico.putShort((short) 1).putShort((short) 32).putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        images.forEach(ico::put);
        Files.write(output.resolve("zordon.ico"), ico.array());
    }
}
