package pl.devstyle.aj.core.oauth2;

public class OAuth2RequestException extends RuntimeException {

    private final OAuth2Error error;
    private final String description;

    public OAuth2RequestException(OAuth2Error error, String description) {
        super(error.getError() + ": " + (description != null ? description : error.getDescription()));
        this.error = error;
        this.description = description;
    }

    public OAuth2Error getError() {
        return error;
    }

    public String getDescription() {
        return description;
    }
}
