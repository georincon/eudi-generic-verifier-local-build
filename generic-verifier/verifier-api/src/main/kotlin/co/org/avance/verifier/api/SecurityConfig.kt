package co.org.avance.verifier.api

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.userdetails.ReactiveUserDetailsService
import reactor.core.publisher.Mono
import org.springframework.security.web.server.SecurityWebFilterChain

/**
 * Solo lo imprescindible es público; todo lo demás se deniega.
 *
 * - rutas bajo `/wallet`: las llama la billetera (sin sesión ni cookies), por eso no hay CSRF ahí.
 * - rutas bajo `/api/academic`: las usan nuestras páginas; el POST exige la cabecera `X-Requested-With`, que una
 *   página de otro origen no puede enviar (no hay CORS configurado).
 * - Sin CORS, sin sesiones, y CSP estricta: nada de scripts ni estilos en línea.
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    /** Sin usuarios: evita que Spring genere (y escriba en el log) una contraseña por defecto que nadie usa. */
    @Bean
    fun noUsers(): ReactiveUserDetailsService = ReactiveUserDetailsService { Mono.empty() }

    @Bean
    fun securityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http
            .csrf { it.disable() }
            .cors { it.disable() }
            .headers { headers ->
                headers.contentSecurityPolicy {
                    it.policyDirectives(
                        "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; " +
                            "frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
                    )
                }
                headers.referrerPolicy { it.policy(org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.NO_REFERRER) }
            }
            .authorizeExchange {
                it.pathMatchers("/", "/verify", "/public/**", "/webjars/**", "/api/academic/**", "/wallet/**", "/actuator/health/**").permitAll()
                it.anyExchange().denyAll()
            }
            .build()
}
