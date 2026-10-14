package co.org.avance.verifier.api.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import java.net.URI

/** Páginas del verificador académico (Thymeleaf). */
@Controller
class AcademicPages {
    @GetMapping("/")
    fun home(): String = "index"

    @GetMapping("/verify")
    fun verify(): String = "verify"
}

/**
 * Prefijo de ruta pública (p. ej. `/verifier` detrás del haproxy del dominio compartido, `""` en local).
 * Las plantillas lo anteponen a sus enlaces, porque el proxy quita el prefijo antes de llegar a la app.
 */
@ControllerAdvice
class ViewModel(@Value("\${verifier.publicUrl}") publicUrl: String) {
    private val base: String = URI(publicUrl).path.orEmpty().trimEnd('/')

    @ModelAttribute("base")
    fun base(): String = base
}
