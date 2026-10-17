#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Generates a small Java EE 8 project (javax.*) for the jakarta rule pack, as a git repository.

It uses Persistence and Validation (an entity), Servlet, JAX-RS, Inject, Annotation and Transaction,
one wildcard import, and the JDK's own javax packages (javax.sql, javax.crypto, javax.naming) and a
javax property key in a string, which must stay. pom.xml has the javax and the jakarta APIs, so the
code compiles before and after the migration; without the javax APIs only migrated code compiles.

Usage: make-jakarta-fixture.py <dir>   (creates <dir>/javaee-project)
"""

import os
import subprocess
import sys

target = os.path.join(os.path.abspath(sys.argv[1]), "javaee-project")
SRC = "src/main/java/com/acme/shop/"
APIS = [("javax.servlet", "javax.servlet-api", "4.0.1"), ("jakarta.servlet", "jakarta.servlet-api", "6.0.0"),
        ("javax.persistence", "javax.persistence-api", "2.2"), ("jakarta.persistence", "jakarta.persistence-api", "3.1.0"),
        ("javax.validation", "validation-api", "2.0.1.Final"), ("jakarta.validation", "jakarta.validation-api", "3.0.2"),
        ("javax.annotation", "javax.annotation-api", "1.3.2"), ("jakarta.annotation", "jakarta.annotation-api", "2.1.1"),
        ("javax.inject", "javax.inject", "1"), ("jakarta.inject", "jakarta.inject-api", "2.0.1"),
        ("javax.ws.rs", "javax.ws.rs-api", "2.1.1"), ("jakarta.ws.rs", "jakarta.ws.rs-api", "3.1.0"),
        ("javax.transaction", "javax.transaction-api", "1.3"), ("jakarta.transaction", "jakarta.transaction-api", "2.0.1")]
deps = "\n".join(f"""        <dependency>
            <groupId>{g}</groupId>
            <artifactId>{a}</artifactId>
            <version>{v}</version>
            <scope>provided</scope>
        </dependency>""" for g, a, v in APIS)
POM = f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.acme</groupId>
    <artifactId>shop</artifactId>
    <version>1.0</version>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <dependencies>
{deps}
    </dependencies>
</project>
"""
FILES = {
    "Product.java": """package com.acme.shop;

import javax.persistence.*;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Size(max = 80)
    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    private Category category;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
""",
    "Category.java": """package com.acme.shop;

import java.util.List;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.OneToMany;

@Entity
public class Category {

    @Id
    private Long id;

    @OneToMany(mappedBy = "category")
    private List<Product> products;
}
""",
    "ProductServlet.java": """package com.acme.shop;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet("/products")
public class ProductServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setContentType("text/plain");
        response.getWriter().println(request.getParameter("q"));
    }
}
""",
    "ProductResource.java": """package com.acme.shop;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/products")
public class ProductResource {

    @Inject
    ProductService service;

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("id") long id) {
        return Response.ok(service.find(id)).build();
    }
}
""",
    "ProductService.java": """package com.acme.shop;

import java.util.Map;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.crypto.Cipher;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.sql.DataSource;
import javax.transaction.Transactional;

public class ProductService {

    /** A JPA property key: a string, not a type reference. */
    static final Map<String, String> PROPERTIES = Map.of("javax.persistence.jdbc.url", "jdbc:h2:mem:shop");

    @PersistenceContext
    EntityManager em;

    DataSource dataSource;

    @PostConstruct
    void init() throws NamingException {
        dataSource = (DataSource) new InitialContext().lookup("java:comp/env/jdbc/shop");
    }

    @PreDestroy
    void close() {
    }

    @Transactional
    public Product find(long id) {
        return em.find(Product.class, id);
    }

    public String algorithm() throws Exception {
        return Cipher.getInstance("AES").getAlgorithm();
    }
}
""",
}

if os.path.exists(target):
    sys.exit(f"{target} exists")
os.makedirs(os.path.join(target, SRC))
open(os.path.join(target, "pom.xml"), "w").write(POM)
open(os.path.join(target, ".gitignore"), "w").write("target/\n")
for name, text in FILES.items():
    open(os.path.join(target, SRC, name), "w").write(text)
for cmd in (["git", "init", "-q"], ["git", "add", "."],
            ["git", "-c", "user.name=fixture", "-c", "user.email=fixture@example.com", "-c", "commit.gpgsign=false",
             "commit", "-q", "-m", "Java EE 8 fixture"]):
    subprocess.run(cmd, cwd=target, check=True)
