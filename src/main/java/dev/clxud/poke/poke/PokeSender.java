package dev.clxud.poke.poke;

/** Transport used to send prompts/wishes to Poke. */
public interface PokeSender {
    void send(String message) throws Exception;
}
