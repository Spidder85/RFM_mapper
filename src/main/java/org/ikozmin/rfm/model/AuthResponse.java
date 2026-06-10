package org.ikozmin.rfm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class AuthResponse {
    private Value value;
    private boolean success;
    private String error;
    private Object[] errors;
    private boolean hasErrors;

    public Value getValue() {
        return value;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getError() {
        return error;
    }

    public Object[] getErrors() {
        return errors;
    }

    public boolean isHasErrors() {
        return hasErrors;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Value {
        private CurrentUser currentUser;
        private String accessToken;
        private Object refreshToken;

        public CurrentUser getCurrentUser() {
            return currentUser;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public Object getRefreshToken() {
            return refreshToken;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class CurrentUser {
        private String id;
        private String userName;
        private String kbShortName;
        private Integer kbLoginType;
        private Boolean isAuthenticated;

        public String getId() {
            return id;
        }

        public String getUserName() {
            return userName;
        }

        public String getKbShortName() {
            return kbShortName;
        }

        public Integer getKbLoginType() {
            return kbLoginType;
        }

        public Boolean getIsAuthenticated() {
            return isAuthenticated;
        }
    }
}
