package nl.rubene.plugwisereader.config;

import org.immutables.value.Value;
import org.jetbrains.annotations.Nullable;

@Value.Immutable
public abstract class InfluxConfig {
    public abstract String url();

    @Nullable
    public abstract String username();

    @Nullable
    public abstract String password();

    @Value.Default
    public String database() {
        return "energy";
    }

    @Value.Default
    public String retentionPolicy() {
        return "autogen";
    }

    @Value.Default
    public String measurement() {
        return "smartmeter";
    }
}
