package cn.har01d.atmaintenance.dto;

import java.util.List;
import java.math.BigInteger;

public record HlsPlaylist(List<String> variants, List<String> segments, List<Double> durations,
                          boolean encrypted, boolean initializationMap, boolean byteRanges, boolean endList,
                          List<HlsKey> segmentKeys, BigInteger mediaSequence, boolean unsupportedEncryption,
                          boolean iFramesOnly) {
}
