package com.exampro.export;

import com.exampro.exception.ExportException;

import java.nio.file.Path;
import java.util.List;

public interface Exporter<T> {
    void export(List<T> items, Path target) throws ExportException;
}
