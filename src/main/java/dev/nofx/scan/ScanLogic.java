package dev.nofx.scan;

public final class ScanLogic {

    private ScanLogic() {
    }

    public static boolean isExploitPreventer(String controlResp, String controlKeybind) {
        return controlResp.equalsIgnoreCase(controlKeybind);
    }

    public static ScanResult evaluate(Probe probe, String resp) {
        if (resp.isEmpty()) {
            return ScanResult.NOT_DETECTED;
        }
        return switch (probe.type()) {
            case KEYBIND -> resp.equalsIgnoreCase(probe.key())
                    ? ScanResult.NOT_DETECTED
                    : ScanResult.DETECTED;
            case TRANSLATE -> {
                String fallback = probe.fallback();
                if (fallback != null && !fallback.isEmpty()
                        && resp.toLowerCase().startsWith(fallback.toLowerCase())) {
                    yield ScanResult.NOT_DETECTED;
                }
                if (resp.equalsIgnoreCase(probe.key())) {
                    yield ScanResult.PROTECTED;
                }
                yield ScanResult.DETECTED;
            }
        };
    }
}