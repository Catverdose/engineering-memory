package com.engineeringmemory.knowledge.service;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.engineeringmemory.common.exception.BusinessException;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.knowledge.config.KnowledgeProperties;

@Component
public class KnowledgeFileExtractor {

	private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "markdown", "log");
	private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

	private final KnowledgeProperties properties;

	public KnowledgeFileExtractor(KnowledgeProperties properties) {
		this.properties = properties;
	}

	public ExtractedDocument extract(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new IllegalArgumentException("업로드할 파일이 비어 있습니다.");
		}
		if (file.getSize() > properties.maxFileBytes()) {
			throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "파일은 20MB를 넘을 수 없습니다.");
		}

		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException e) {
			throw new IllegalArgumentException("업로드 파일을 읽을 수 없습니다.", e);
		}
		if (bytes.length > properties.maxFileBytes()) {
			throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "파일은 20MB를 넘을 수 없습니다.");
		}

		String sourceName = safeFilename(file.getOriginalFilename());
		String extension = extensionOf(sourceName);
		String contentType = normalizeContentType(file.getContentType());
		boolean extensionlessReadme = "README".equalsIgnoreCase(sourceName);
		String extracted;
		String mediaType;

		if ("pdf".equals(extension)) {
			if (!startsWith(bytes, PDF_SIGNATURE)) {
				throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE,
						"확장자는 PDF지만 PDF 파일 시그니처가 없습니다.");
			}
			if (!contentType.isEmpty()
					&& !"application/pdf".equals(contentType)
					&& !"application/octet-stream".equals(contentType)) {
				throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE,
						"PDF 파일의 Content-Type이 올바르지 않습니다.");
			}
			extracted = extractPdf(bytes);
			mediaType = "application/pdf";
		} else if (TEXT_EXTENSIONS.contains(extension) || extensionlessReadme) {
			if (startsWith(bytes, PDF_SIGNATURE)) {
				throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE,
						"PDF 파일은 .pdf 확장자로 등록해야 합니다.");
			}
			if (!contentType.isEmpty()
					&& !contentType.startsWith("text/")
					&& !"application/octet-stream".equals(contentType)) {
				throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE,
						"텍스트 파일의 Content-Type이 올바르지 않습니다.");
			}
			extracted = decodeUtf8(bytes);
			mediaType = switch (extension) {
				case "md", "markdown" -> "text/markdown;charset=UTF-8";
				default -> "text/plain;charset=UTF-8";
			};
		} else {
			throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE,
					"지원 파일 형식은 README, txt, md, markdown, log, pdf 입니다.");
		}

		String normalized = normalizeText(extracted);
		validateExtractedText(normalized);
		return new ExtractedDocument(normalized, bytes, sourceName, mediaType);
	}

	public String validateText(String content) {
		String normalized = normalizeText(content);
		validateExtractedText(normalized);
		byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > properties.maxFileBytes()) {
			throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
					"텍스트 원본은 UTF-8 기준 20MB를 넘을 수 없습니다.");
		}
		return normalized;
	}

	private String extractPdf(byte[] bytes) {
		try (PDDocument document = Loader.loadPDF(bytes)) {
			if (document.isEncrypted()) {
				throw new IllegalArgumentException("암호화된 PDF는 등록할 수 없습니다.");
			}
			if (document.getNumberOfPages() > properties.maxPdfPages()) {
				throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
						"PDF는 %d페이지를 넘을 수 없습니다.".formatted(properties.maxPdfPages()));
			}
			LimitedTextWriter writer = new LimitedTextWriter(properties.maxExtractedChars());
			new PDFTextStripper().writeText(document, writer);
			return writer.content();
		} catch (BusinessException | IllegalArgumentException e) {
			throw e;
		} catch (TextLimitExceededException e) {
			throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
					"PDF에서 추출된 텍스트는 %,d자를 넘을 수 없습니다."
							.formatted(properties.maxExtractedChars()), e);
		} catch (IOException | RuntimeException e) {
			throw new IllegalArgumentException("손상됐거나 읽을 수 없는 PDF입니다.", e);
		}
	}

	private static String decodeUtf8(byte[] bytes) {
		try {
			String decoded = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes))
					.toString();
			return decoded.startsWith("\uFEFF") ? decoded.substring(1) : decoded;
		} catch (CharacterCodingException e) {
			throw new IllegalArgumentException("텍스트 파일은 올바른 UTF-8이어야 합니다.", e);
		}
	}

	private void validateExtractedText(String text) {
		if (text.isBlank()) {
			throw new IllegalArgumentException("파일에서 검색할 텍스트를 찾지 못했습니다.");
		}
		if (text.length() > properties.maxExtractedChars()) {
			throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
					"추출된 텍스트는 %,d자를 넘을 수 없습니다."
							.formatted(properties.maxExtractedChars()));
		}
		if (text.indexOf('\0') >= 0) {
			throw new IllegalArgumentException("텍스트에 NUL 문자를 포함할 수 없습니다.");
		}
	}

	private static String normalizeText(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("\r\n", "\n").replace('\r', '\n').strip();
	}

	private static String safeFilename(String original) {
		String candidate = original == null ? "document" : original;
		candidate = candidate.replace('\\', '/');
		candidate = candidate.substring(candidate.lastIndexOf('/') + 1)
				.replaceAll("[\\p{Cntrl}]", "")
				.strip();
		if (candidate.isBlank()) {
			candidate = "document";
		}
		return candidate.length() <= 300 ? candidate : candidate.substring(candidate.length() - 300);
	}

	private static String extensionOf(String filename) {
		int dot = filename.lastIndexOf('.');
		return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	private static String normalizeContentType(String value) {
		if (value == null) {
			return "";
		}
		int semicolon = value.indexOf(';');
		return (semicolon >= 0 ? value.substring(0, semicolon) : value).trim().toLowerCase(Locale.ROOT);
	}

	private static boolean startsWith(byte[] value, byte[] prefix) {
		if (value.length < prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if (value[i] != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	private static final class LimitedTextWriter extends Writer {

		private final int maxChars;
		private final StringBuilder content;

		private LimitedTextWriter(int maxChars) {
			this.maxChars = maxChars;
			this.content = new StringBuilder(Math.min(maxChars, 16_384));
		}

		@Override
		public void write(char[] chars, int offset, int length) throws IOException {
			if (length < 0 || offset < 0 || offset + length > chars.length) {
				throw new IndexOutOfBoundsException();
			}
			if ((long) content.length() + length > maxChars) {
				throw new TextLimitExceededException();
			}
			content.append(chars, offset, length);
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
		}

		private String content() {
			return content.toString();
		}
	}

	private static final class TextLimitExceededException extends IOException {
		private static final long serialVersionUID = 1L;
	}
}
