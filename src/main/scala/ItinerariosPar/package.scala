import Datos._
import common._
import scala.collection.parallel.CollectionConverters._

package object ItinerariosPar {

  type Itinerario = List[Vuelo]

  /**
   * Recibe una hora y minutos, además de un GMT, y convierte la hora a una hora absoluta usando
   * el GMT
   *
   * @param hora
   * @param minutos
   * @param gmt
   * @return Int con la hora absoluta
   */
  def convertirAHoraAbsoluta(hora: Int, minutos: Int, gmt: Int): Int = {
    (hora + gmt / 100) * 60 + minutos
  }

  /**
   * Recibe una lista de aeropuertos, un código de aeropuerto y obtiene el GMT del aeropuerto
   * con ese código
   *
   * @param aeropuertos
   * @param codigo
   * @return
   */
  def obtenerGMT(aeropuertos: List[Aeropuerto], codigo: String): Int = {
    aeropuertos.find(_.Cod == codigo).map(_.GMT).getOrElse(0)
  }


  /**
   * Recibe un vuelo, una lista de aeropuertos y convierte la hora de llegada y salida de ese vuelo
   * a hora absoluta usando el GMT de los aeropuertos de origen y destino
   *
   * @param vuelo
   * @param aeropuertos
   * @return
   */
  def convertirVuelosAHorasAbsolutas(vuelo: Vuelo, aeropuertos: List[Aeropuerto]): (Int, Int) = {
    val gmtOrg = obtenerGMT(aeropuertos, vuelo.Org)
    val gmtDst = obtenerGMT(aeropuertos, vuelo.Dst)
    val horaSalida = convertirAHoraAbsoluta(vuelo.HS, vuelo.MS, gmtOrg)
    val horaLlegada = convertirAHoraAbsoluta(vuelo.HL, vuelo.ML, gmtDst)
    (horaSalida, horaLlegada)
  }


  /**
   * Recibe un itinerario y una lista de aeropuertos y verifica si ese itinerario es válido, es decir, que
   * para cada par de vuelos consecutivos se cumpla que la hora de llegada del primero sea menor que
   * la hora de salida del siguiente
   *
   * @param itinerario
   * @param aeropuertos
   * @return Boolean indicando si es válido o no
   */
  def itinerarioValido(itinerario: Itinerario, aeropuertos: List[Aeropuerto]): Boolean = {
    itinerario.zip(itinerario.tail).forall { case (vueloActual, vueloSiguiente) =>
      val (_, llegadaActual) = convertirVuelosAHorasAbsolutas(vueloActual, aeropuertos)
      val (salidaSiguiente, _) = convertirVuelosAHorasAbsolutas(vueloSiguiente, aeropuertos)
      llegadaActual < salidaSiguiente
    }
  }


  // Punto 3.1

  /**
   * Recibe una lista de vuelos y una lista de aeropuertos y devuelve una función que recibe los códigos de un
   * aeropuerto origen y destino, y devuelve una lista de todos los itinerarios para ir del origen al destino,
   * encontrándolos de manera paralela
   *
   * @param vuelos
   * @param aeropuertos
   * @return
   */
  def itinerariosPar(vuelos: List[Vuelo], aeropuertos: List[Aeropuerto]): (String, String) => List[Itinerario] = {

    def encontrarItinerarios(origen: String, destino: String, vuelosDisponibles: List[Vuelo], visitados: Set[String]): List[Itinerario] = {
      if (origen == destino) {
        return List(Nil)
      }

      val tareasItinerarios = for {
        vuelo <- vuelosDisponibles.filter(_.Org == origen)
        if (!visitados.contains(vuelo.Dst))
      } yield task {
        val subItinerarios = encontrarItinerarios(vuelo.Dst, destino, vuelosDisponibles, visitados + origen)
        subItinerarios.map(it => vuelo :: it)
      }

      tareasItinerarios.flatMap(tarea => tarea.join())
    }

    (cod1: String, cod2: String) => {
      val posiblesItinerarios = encontrarItinerarios(cod1, cod2, vuelos, Set())
      posiblesItinerarios.filter(itinerario => itinerarioValido(itinerario, aeropuertos))
    }
  }


  // Punto 3.2

  /**
   * Recibe un itineario y una lista de aeropuertos y calcula del tiempo total que se demora ese itineario en llegar
   * del origen al destino, tomando en cuenta el tiempo de vuelo y de espera
   *
   * @param itinerario
   * @param aeropuertos
   * @return
   */
  def calcularTiempoTotalItinerario(itinerario: Itinerario, aeropuertos: List[Aeropuerto]): Int = {
    // Para cada vuelo obtiene una tupla con la hora de salida y de llegada del mismo
    val tiempos = itinerario.map(vuelo => convertirVuelosAHorasAbsolutas(vuelo, aeropuertos))
    // Calcula el tiempo de espera en tierra para todos los vuelos
    val tiempoEspera = tiempos.zip(tiempos.tail).map { case ((_, llegadaActual), (salidaSiguiente, _)) =>
      (salidaSiguiente - llegadaActual).abs
    }.sum
    // Calcula el tiempo de vuelo restando para cada vuelo horaLlegada - horaSalida y sumándolos
    val tiempoVuelo = tiempos.map { case (salida, llegada) => (llegada - salida).abs }.sum
    // Devuelve el total de tiempo de vuelo y espera
    tiempoVuelo + tiempoEspera
  }

  /**
   * Recibe una lista de vuelos y una lista de aeropuertos y devuelve una función que recibe los códigos de un
   * aeropuerto origen y otro destino y retorna una lista con los 3 itinerarios que minimizan el tiempo total para ir
   * del origen al destino
   *
   * @param vuelos
   * @param aeropuertos
   * @return
   */
  def itinerariosTiempoPar(vuelos: List[Vuelo], aeropuertos: List[Aeropuerto]): (String, String) => List[Itinerario] = {
    val obtenerItinerarios = itinerariosPar(vuelos, aeropuertos)

    (cod1: String, cod2: String) => {
      val posiblesItinerarios = obtenerItinerarios(cod1, cod2)
      //Para cada itineario, crea un nuevo hilo que devuelve una tupla con el itineario y el tiempo total que se toma este
      val itinerariosConTiempo = posiblesItinerarios.map(it => task((it, calcularTiempoTotalItinerario(it, aeropuertos))))
      // Obtiene el resultado de cada hilo y ordena los itinearios de acuerdo al tiempo que se toma cada uno,
      // dejando solo los tres más rápidos
      val itinerariosOrdenados = itinerariosConTiempo.map(it => it.join()).sortBy(_._2).take(3)
      // Devuelve el primer elemento de cada tupla, que corresponde al itinerario
      itinerariosOrdenados.map(_._1)
    }
  }


  // Punto 3.3

  /**
   * Recibe una lista de vuelos, una lista de aeropuertos, y devuelve una función que recibe los códigos de un
   * aeropuerto origen y destino y devuelve una lista con los itinearios que minimizan las escalas para ir del
   * origen al destino
   *
   * @param vuelos
   * @param aeropuertos
   * @return
   */
  def itinerariosEscalasPar(vuelos: List[Vuelo], aeropuertos: List[Aeropuerto]): (String, String) => List[Itinerario] = {
    val obtenerItinearios = itinerariosPar(vuelos, aeropuertos)

    (cod1: String, cod2: String) => {
      val posiblesItinearios = obtenerItinearios(cod1, cod2)
      val itinerariosConEscalas = posiblesItinearios.map(itinerario => task((itinerario, itinerario.map(_.Esc).sum + (itinerario.length - 1))))
      val itinerariosOrdenados = itinerariosConEscalas.map(_.join()).sortBy(_._2).take(3)
      itinerariosOrdenados.map(_._1)
    }
  }


  // Punto 3.4

  /**
   * Calcula el tiempo de vuelo de un itinerario
   *
   * @param itinerario
   * @param aeropuertos
   * @return tiempoVuelo total para el itineario
   */
  def calcularTiempoVueloItinerario(itinerario: Itinerario, aeropuertos: List[Aeropuerto]): Int = {
    // Extrae las parejas de (horaSalida, horaLlegada) para cada vuelo del itinerario
    val tiempos = itinerario.map(vuelo => convertirVuelosAHorasAbsolutas(vuelo, aeropuertos))
    // Calcula el tiempo de vuelo para cada vuelo restando horaLlegada - horaSalida y sumándolos
    val tiempoVuelo = tiempos.map { case (salida, llegada) => (llegada - salida).abs }.sum
    tiempoVuelo
  }

  /**
   * Recibe una lista de vuelos y una lista de aeropuertos y devuelve una función que recibe los códigos de un
   * aeropuerto origen y otro destino y calcula los 3 itinerarios (si los hay)
   * que minimizan el tiempo de vuelo para ir del origen al destino
   *
   * @param vuelos
   * @param aeropuertos
   * @return
   */
  def itinerariosAirePar(vuelos: List[Vuelo], aeropuertos: List[Aeropuerto]): (String, String) => List[Itinerario] = {
    val obtenerItinerarios = itinerariosPar(vuelos, aeropuertos)

    (cod1: String, cod2: String) => {
      val posiblesItinerarios = obtenerItinerarios(cod1, cod2)
      val itinerariosConTiempoVuelo = posiblesItinerarios.map(it => task((it, calcularTiempoVueloItinerario(it, aeropuertos))))
      val itinerariosOrdenados = itinerariosConTiempoVuelo.map(it => it.join()).sortBy(_._2).take(3)
      itinerariosOrdenados.map(_._1)
    }
  }


  // Punto 3.5

  /**
   * Recibe una lista de vuelos y una lista de aeropuertos y devuelve una función que recibe los códigos de un
   * aeropuerto origen y otro destino, además de una cantidad de horas y minutos (que representan la hora de una cita)
   * y calcula el itinerario que maximiza el tiempo de salida para poder llegar a tiempo a la cita
   *
   * @param vuelos
   * @param aeropuertos
   * @return
   */
  def itinerarioSalidaPar(vuelos: List[Vuelo], aeropuertos: List[Aeropuerto]): (String, String, Int, Int) => Itinerario = {
    val obtenerItinerarios = itinerariosPar(vuelos, aeropuertos)

    (cod1: String, cod2: String, hc: Int, mc: Int) => {
      // Calcula los posibles itinearios para ir de cod1 a cod2
      val posiblesItinerarios = obtenerItinerarios(cod1, cod2)
      // Convierte la hora de la cita a hora absoluta
      val citaEnHoraAbsoluta = convertirAHoraAbsoluta(hc, mc, obtenerGMT(aeropuertos, cod2))
      // Filtra los posibles itinearios dejando solo aqueellos cuyo hora de llegada al destino
      // es menor a la hora de la cita
      val tareas = posiblesItinerarios.map { it => task {
        val (_, horaLlegada) = convertirVuelosAHorasAbsolutas(it.last, aeropuertos)
        (it, horaLlegada)
        }
      }
      val itinerariosValidos = tareas.map(_.join()).filter(_._2 <= citaEnHoraAbsoluta)
      // Si no hay itinerarios validos se devuelve una lsita vacía
      if (itinerariosValidos.isEmpty) List()
      else {
        // Toma solo el itinerario cuya hora de salida es mayor
        val mejorItineario = itinerariosValidos.maxBy { it =>
          val (_, horaSalida) = convertirVuelosAHorasAbsolutas(it._1.head, aeropuertos)
          horaSalida
        }
        mejorItineario._1
      }
    }
  }
}
