package cc.blynk.server.core.dao;

import cc.blynk.server.core.model.enums.GraphType;
import cc.blynk.server.core.model.enums.PinType;
import cc.blynk.server.core.protocol.exceptions.NoDataException;
import cc.blynk.server.core.reporting.GraphPinRequest;
import cc.blynk.server.core.reporting.average.AverageAggregator;
import cc.blynk.utils.FileUtils;
import cc.blynk.utils.ServerProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static cc.blynk.utils.ReportingUtil.EMPTY_ARRAY;
import static java.lang.String.format;

/**
 * The Blynk Project.
 * Created by Dmitriy Dumanskiy.
 * Created on 2/18/2015.
 */
public class ReportingDao {

    public static final String REPORTING_MINUTE_FILE_NAME = "history_%s_%c%d_minute.bin";
    public static final String REPORTING_HOURLY_FILE_NAME = "history_%s_%c%d_hourly.bin";
    public static final String REPORTING_DAILY_FILE_NAME = "history_%s_%c%d_daily.bin";
    private static final Logger log = LogManager.getLogger(ReportingDao.class);
    private final AverageAggregator averageAggregator;
    private final String dataFolder;

    private final boolean ENABLE_RAW_DATA_STORE;

    public ReportingDao(String reportingFolder, AverageAggregator averageAggregator, ServerProperties serverProperties) {
        this.averageAggregator = averageAggregator;
        this.dataFolder = reportingFolder;
        this.ENABLE_RAW_DATA_STORE = serverProperties.getBoolProperty("enable.raw.data.store");
    }

    public static String generateFilename(int dashId, PinType pinType, byte pin, GraphType type) {
        switch (type) {
            case MINUTE :
                return format(REPORTING_MINUTE_FILE_NAME, dashId, pinType.pintTypeChar, pin);
            case HOURLY :
                return format(REPORTING_HOURLY_FILE_NAME, dashId, pinType.pintTypeChar, pin);
            default :
                return format(REPORTING_DAILY_FILE_NAME, dashId, pinType.pintTypeChar, pin);
        }
    }

    public static ByteBuffer getByteBufferFromDisk(String dataFolder, String username, int dashId, PinType pinType, byte pin, int count, GraphType type) {
        Path userDataFile = Paths.get(dataFolder, username, generateFilename(dashId, pinType, pin, type));
        if (Files.notExists(userDataFile)) {
            return null;
        }

        try {
            return read(userDataFile, count);
        } catch (IOException ioe) {
            log.error(ioe);
        }

        return null;
    }

    //todo filter outdated records?
    private static ByteBuffer read(Path userDataFile, int count) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(userDataFile, StandardOpenOption.READ)) {
            final int size = (int) Files.size(userDataFile);
            final int dataSize = count * 16;
            final int readDataSize = Math.min(dataSize, size);

            ByteBuffer buf = ByteBuffer.allocate(readDataSize);
            channel.position(Math.max(0, size - dataSize));
            channel.read(buf);
            return buf;
        }
    }

    private static boolean checkNoData(byte[][] data) {
        boolean noData = true;

        for (byte[] pinData : data) {
            noData = noData && pinData.length == 0;
        }

        return noData;
    }

    public ByteBuffer getByteBufferFromDisk(String username, int dashId, PinType pinType, byte pin, int count, GraphType type) {
        return getByteBufferFromDisk(dataFolder, username, dashId, pinType, pin, count, type);
    }

    public void delete(String username, int dashId, PinType pinType, byte pin) {
        log.debug("Removing {}{} pin data for dashId {}.", pinType.pintTypeChar, pin, dashId);
        Path userDataMinuteFile = Paths.get(dataFolder, username, format(REPORTING_MINUTE_FILE_NAME, dashId, pinType.pintTypeChar, pin));
        Path userDataHourlyFile = Paths.get(dataFolder, username, format(REPORTING_HOURLY_FILE_NAME, dashId, pinType.pintTypeChar, pin));
        Path userDataDailyFile = Paths.get(dataFolder, username, format(REPORTING_DAILY_FILE_NAME, dashId, pinType.pintTypeChar, pin));
        FileUtils.deleteQuietly(userDataMinuteFile);
        FileUtils.deleteQuietly(userDataHourlyFile);
        FileUtils.deleteQuietly(userDataDailyFile);
    }

    public void process(String username, int dashId, byte pin, PinType pinType, String value, long ts) {
        if (ENABLE_RAW_DATA_STORE) {
            ThreadContext.put("dashId", Integer.toString(dashId));
            ThreadContext.put("pin", String.valueOf(pinType.pintTypeChar) + pin);
            log.info("{},{}", value, ts);
        }

        averageAggregator.collect(username, dashId, pinType, pin, ts, value);
    }

    public void process(String username, int dashId, byte pin, PinType pinType, String value) {
        process(username, dashId, pin, pinType, value, System.currentTimeMillis());
    }

    public byte[][] getAllFromDisk(String username, GraphPinRequest[] requestedPins) {
        byte[][] values = new byte[requestedPins.length][];

        for (int i = 0; i < requestedPins.length; i++) {
            final ByteBuffer byteBuffer = getByteBufferFromDisk(username,
                    requestedPins[i].dashId, requestedPins[i].pinType,
                    requestedPins[i].pin, requestedPins[i].count, requestedPins[i].type);
            values[i] =  byteBuffer == null ? EMPTY_ARRAY : byteBuffer.array();
        }


        if (checkNoData(values)) {
            throw new NoDataException();
        }

        return values;
    }

}
