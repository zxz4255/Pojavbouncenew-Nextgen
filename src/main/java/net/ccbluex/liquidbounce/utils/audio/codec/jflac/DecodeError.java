// Adapted from the MuoniumPlayer reference; original copyright and license headers retained.
package net.ccbluex.liquidbounce.utils.audio.codec.jflac;

import java.io.IOException;

public class DecodeError extends IOException {

    public DecodeError(String message) {
        super(message);
    }

}
