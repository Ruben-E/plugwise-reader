package nl.rubene.plugwisereader;

import nl.rubene.plugwisereader.config.ImmutableInfluxConfig;
import nl.rubene.plugwisereader.config.ImmutablePlugwiseConfig;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.Optional.ofNullable;

public class ReaderMain {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReaderMain.class);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public static void main(String[] args) {
        new ReaderMain().start();
    }

    private void start() {
        registerShutdownHook();
        registerDefaultUncaughtExceptionHandler();

        ImmutableInfluxConfig.Builder configBuilder = ImmutableInfluxConfig.builder();
        ofNullable(System.getenv("influx_url")).ifPresent(configBuilder::url);
        ofNullable(System.getenv("influx_username")).ifPresent(configBuilder::username);
        ofNullable(System.getenv("influx_password")).ifPresent(configBuilder::password);
        ofNullable(System.getenv("influx_database")).ifPresent(configBuilder::database);
        ofNullable(System.getenv("influx_retention_policy")).ifPresent(configBuilder::retentionPolicy);
        ofNullable(System.getenv("influx_measurement")).ifPresent(configBuilder::measurement);
        ImmutableInfluxConfig influxConfig = configBuilder.build();

        ImmutablePlugwiseConfig.Builder plugwiseConfigBuilder = ImmutablePlugwiseConfig.builder();
        ofNullable(System.getenv("plugwise_ip")).ifPresent(plugwiseConfigBuilder::ip);
        ofNullable(System.getenv("plugwise_username")).ifPresent(plugwiseConfigBuilder::username);
        ofNullable(System.getenv("plugwise_password")).ifPresent(plugwiseConfigBuilder::password);
        ImmutablePlugwiseConfig plugwiseConfig = plugwiseConfigBuilder.build();

        OkHttpClient client = new OkHttpClient();
        InfluxDB influxDB;
        if (influxConfig.username() != null && influxConfig.password() != null) {
            influxDB= InfluxDBFactory.connect(influxConfig.url(), influxConfig.username(), influxConfig.password());
        } else {
            influxDB = InfluxDBFactory.connect(influxConfig.url());
        }

        scheduler.scheduleAtFixedRate(() -> {
            Request request = new Request.Builder()
                    .get()
                    .header("Authorization", Credentials.basic(plugwiseConfig.username(), plugwiseConfig.password()))
                    .url(String.format("http://%s/core/modules", plugwiseConfig.ip()))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = builderFactory.newDocumentBuilder();
                Document xmlDocument = builder.parse(response.body().byteStream());
                XPath xPath = XPathFactory.newInstance().newXPath();

                Node electricityConsumedNode = (Node) xPath.compile("//services/electricity_point_meter/measurement[@directionality='consumed']").evaluate(xmlDocument, XPathConstants.NODE);
                Node electricityProducedNode = (Node) xPath.compile("//services/electricity_point_meter/measurement[@directionality='produced']").evaluate(xmlDocument, XPathConstants.NODE);
                Node gasConsumedCumulativeNode = (Node) xPath.compile("//services/gas_cumulative_meter/measurement[@directionality='consumed']").evaluate(xmlDocument, XPathConstants.NODE);
                double electricityConsumed = Double.parseDouble(electricityConsumedNode.getTextContent());
                double electricityProduced = Double.parseDouble(electricityProducedNode.getTextContent());
                double gasConsumedCumulative = Double.parseDouble(gasConsumedCumulativeNode.getTextContent());

                LOGGER.info("electricityConsumed: {}", electricityConsumed);
                LOGGER.info("electricityProduced: {}", electricityProduced);
                LOGGER.info("gasConsumedCumulative: {}", gasConsumedCumulative);

                influxDB.write(influxConfig.database(), influxConfig.retentionPolicy(), Point.measurement(influxConfig.measurement())
                        .time(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                        .addField("electricityConsumed", electricityConsumed)
                        .addField("electricityProduced", electricityProduced)
                        .addField("gasConsumedCumulative", gasConsumedCumulative)
                        .build());

            } catch (IOException | ParserConfigurationException | SAXException | XPathExpressionException e) {
                LOGGER.warn("Exception during parsing / calling", e);
            }
        }, 0, 20, TimeUnit.SECONDS);
    }

    private void stop() {
        scheduler.shutdownNow();
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down reader...");
            stop();
        }));
    }

    private void registerDefaultUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            LOGGER.error("Got an uncaught exception, this was not expected so stopping program.", e);
            stop();
        });
    }
}
