package nl.rubene.plugwisereader.config;

import org.immutables.value.Value;

@Value.Immutable
public abstract class PlugwiseConfig {
    public abstract String ip();

    public abstract String username();

    public abstract String password();
}
