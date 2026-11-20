package com.fooyre.gestion;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Appels HTTP du catalogue pour les tests (jeton Bearer d'une session réelle). */
public final class CatalogTestClient {

    private final MockMvc mvc;
    private final String bearer;

    public CatalogTestClient(MockMvc mvc, String bearer) {
        this.mvc = mvc;
        this.bearer = bearer;
    }

    /** Corps JSON simple : valeurs {@code null} → {@code null}, chaînes échappées minimalement. */
    public static String json(Map<String, Object> fields) {
        return fields.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":" + value(entry.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String value(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) map;
            return json(nested);
        }
        if (value instanceof java.util.Collection<?> list) {
            return list.stream().map(CatalogTestClient::value).collect(Collectors.joining(",", "[", "]"));
        }
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    static Map<String, Object> product(String name, String salePrice) {
        return fields("name", name, "unit", "PIECE", "salePrice", salePrice);
    }

    ResultActions getJson(String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", bearer));
    }

    ResultActions postJson(String path, Map<String, Object> body) throws Exception {
        return mvc.perform(post(path).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    ResultActions putJson(String path, Map<String, Object> body) throws Exception {
        return mvc.perform(put(path).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    ResultActions delete(String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path)
                .header("Authorization", bearer));
    }

    /** Crée une catégorie et renvoie son identifiant. */
    String createCategory(String name) throws Exception {
        String body = postJson("/api/v1/categories", fields("name", name)).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** Crée un produit et renvoie son identifiant. */
    public String createProduct(Map<String, Object> fields) throws Exception {
        String body = postJson("/api/v1/products", fields).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    public static String read(ResultActions actions, String path) throws Exception {
        Object value = JsonPath.read(actions.andReturn().getResponse().getContentAsString(), path);
        return String.valueOf(value);
    }
}
