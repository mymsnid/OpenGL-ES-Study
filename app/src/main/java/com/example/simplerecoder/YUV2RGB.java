package com.askey.dvr.cdr7010.dashcam.simplerecoder;

import java.nio.ByteBuffer;

public class YUV2RGB {

    private static int R = 0;
    private static int G = 1;
    private static int B = 2;

    public static void I420ToRGB(ByteBuffer input,ByteBuffer output, int width, int height) {
        int numOfPixel = width * height;
        int positionOfV = numOfPixel;
        int positionOfU = numOfPixel / 4 + numOfPixel;
        input.position(0);
        output.clear();
        for (int i = 0; i < height; i++) {
            int startY = i * width;
            int step = (i / 2) * (width / 2);
            int startU = positionOfV + step;
            int startV = positionOfU + step;
            for (int j = 0; j < width; j++) {
                int Y = startY + j;
                int U = startU + j / 2;
                int V = startV + j / 2;
                int index = Y * 4;
                RGB tmp = yuvTorgb(input.get(Y), input.get(U), input.get(V));
                output.put(index + R,(byte)tmp.r);
                output.put(index + G,(byte)tmp.g);
                output.put(index + B,(byte)tmp.b);
                output.put(index + 3,(byte)0);
            }
        }
    }

    private static class RGB {
        public int r, g, b;
    }

    private static RGB yuvTorgb(byte Y, byte U, byte V) {
        RGB rgb = new RGB();
        rgb.r = (int) ((Y & 0xff) + 1.4075 * ((V & 0xff) - 128));
        rgb.g = (int) ((Y & 0xff) - 0.3455 * ((U & 0xff) - 128) - 0.7169 * ((V & 0xff) - 128));
        rgb.b = (int) ((Y & 0xff) + 1.779 * ((U & 0xff) - 128));
        rgb.r = (rgb.r < 0 ? 0 : rgb.r > 255 ? 255 : rgb.r);
        rgb.g = (rgb.g < 0 ? 0 : rgb.g > 255 ? 255 : rgb.g);
        rgb.b = (rgb.b < 0 ? 0 : rgb.b > 255 ? 255 : rgb.b);
        return rgb;

    }
}
