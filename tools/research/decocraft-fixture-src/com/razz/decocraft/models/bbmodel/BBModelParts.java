package com.razz.decocraft.models.bbmodel;

import java.util.LinkedHashMap;
import java.util.Map;

/** Contract-test stand-in for the parser element/face classes. Never packaged. */
public class BBModelParts {
    public static class Element {
        public Map<Object, Object> faces = new LinkedHashMap<>();
    }

    public static class Face {
        public int texture;
    }
}
