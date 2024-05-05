/*
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.example.esql;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._helpers.bulk.BulkIngester;
import co.elastic.clients.elasticsearch._helpers.esql.jdbc.ResultSetEsqlAdapter;
import co.elastic.clients.elasticsearch._helpers.esql.objects.ObjectsEsqlAdapter;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;

import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

public class EsqlArticle {

    public static void main(String[] args) throws IOException, SQLException, CertificateException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        String dir = System.getProperty("user.dir");
        System.out.println(dir);

        Properties prop = new Properties();
        Path path = Paths.get(dir, "src", "main", "resources", "application" +
                ".conf");
        prop.load(new FileInputStream(path.toString()));

        String serverUrl = prop.getProperty("server-url");
        String apiKey = prop.getProperty("api-key");
        String csvPath = prop.getProperty("csv-file");
        String certPath = prop.getProperty("cert_path");

        System.out.println("serverUrl:  " + serverUrl);
        System.out.println("apiKey:  " + apiKey);
        System.out.println("csvPath:  " + csvPath);
        System.out.println("certPath:  " + certPath);

        Path caCertificatePath = Paths.get(certPath);
        CertificateFactory factory =
                CertificateFactory.getInstance("X.509");
        Certificate trustedCa;
        try (InputStream is = Files.newInputStream(caCertificatePath)) {
            trustedCa = factory.generateCertificate(is);
        }
        KeyStore trustStore = KeyStore.getInstance("pkcs12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("ca", trustedCa);
        SSLContextBuilder sslContextBuilder = SSLContexts.custom()
                .loadTrustMaterial(trustStore, null);
        final SSLContext sslContext = sslContextBuilder.build();

        RestClient restClient = RestClient
                .builder(HttpHost.create(serverUrl))
                .setDefaultHeaders(new Header[]{
                        new BasicHeader("Authorization", "ApiKey " + apiKey)
                })
                .setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                    @Override
                    public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpAsyncClientBuilder) {
                        return httpAsyncClientBuilder.setSSLContext(sslContext);
                    }
                })
                .build();

        System.out.println(restClient.isRunning());

        ObjectMapper mapper = JsonMapper.builder()
                .build();

        JacksonJsonpMapper jsonpMapper = new JacksonJsonpMapper(mapper);

        ElasticsearchTransport transport = new RestClientTransport(
                restClient, jsonpMapper);

        ElasticsearchClient client = new ElasticsearchClient(transport);

        final String INDEX_NAME = "books";
        // Delete the index if it exists
        if (client.indices().exists(ex -> ex.index(INDEX_NAME)).value()) {
            client.indices().delete(d -> d
                    .index(INDEX_NAME)
            );
        }

        if (!client.indices().exists(ex -> ex.index(INDEX_NAME)).value()) {
            client.indices()
                    .create(c -> c
                            .index(INDEX_NAME)
                            .mappings(mp -> mp
                                    .properties("title", p -> p.text(t -> t))
                                    .properties("description", p -> p.text(t -> t))
                                    .properties("author", p -> p.text(t -> t))
                                    .properties("image", p -> p.text(t -> t))
                                    .properties("previewLink", p -> p.text(t -> t))
                                    .properties("publisher", p -> p.text(t -> t))
                                    .properties("year", p -> p.short_(s -> s))
                                    .properties("infoLink", p -> p.text(t -> t))
                                    .properties("categories", p -> p.text(t -> t))
                                    .properties("ratings", p -> p.halfFloat(hf -> hf))
                            ));
        }

        Instant start = Instant.now();
        System.out.println("Starting BulkIndexer... \n");

        CsvMapper csvMapper = new CsvMapper();
        CsvSchema schema = CsvSchema.builder()
                .addColumn("title") // same order as in the csv
                .addColumn("description")
                .addColumn("author")
                .addColumn("image")
                .addColumn("previewLink")
                .addColumn("publisher")
                .addColumn("year")
                .addColumn("infoLink")
                .addColumn("categories")
                .addColumn("ratings")
                .setColumnSeparator(',')
                .setSkipFirstDataRow(true)
                .build();

        MappingIterator<Book> it = csvMapper
                .readerFor(Book.class)
                .with(schema)
                .readValues(new FileReader(csvPath));

        System.out.println(it);

        BulkIngester ingester = BulkIngester.of(bi -> bi
                .client(client)
                .maxConcurrentRequests(20)
                .maxOperations(5000));

        boolean hasNext = true;

        int j = 0;
        while (hasNext) {
            try {
                Book book = it.nextValue();
                ingester.add(BulkOperation.of(b -> b
                        .index(i -> i
                                .index(INDEX_NAME)
                                .document(book))));
                hasNext = it.hasNextValue();
            } catch (JsonParseException | InvalidFormatException e) {
                // ignore malformed data
                System.out.println("Something is wrong at: " + j);
            }
            j ++;
        }

        ingester.close();

        client.indices().refresh();

        Instant end = Instant.now();

        System.out.println("Finished in: " + Duration.between(start, end).toMillis() + "\n");

        String queryAuthor =
                """
                    from books
                    | where author == "['Julie Strain']"
                    | sort year desc
                    | limit 10
                """;

        List<Book> queryRes = (List<Book>) client.esql().query(ObjectsEsqlAdapter.of(Book.class), queryAuthor);

        System.out.println("~~~\nObject result author:\n" + queryRes.stream().map(Book::title).collect(Collectors.joining("\n")));

        ResultSet resultSet = client.esql().query(ResultSetEsqlAdapter.INSTANCE, queryAuthor);

        System.out.println("~~~\nResultSet result author:");
        while (resultSet.next()) {
            System.out.println(resultSet.getString("title"));
        }

        String queryPublisher =
                """
                    from books
                    | where publisher == "Plympton PressIntl"
                    | sort ratings desc
                    | limit 10
                    | sort title asc
                """;

        queryRes = (List<Book>) client.esql().query(ObjectsEsqlAdapter.of(Book.class), queryPublisher);
        System.out.println("~~~\nObject result publisher:\n" + queryRes.stream().map(Book::title).collect(Collectors.joining("\n")));
    }
}
