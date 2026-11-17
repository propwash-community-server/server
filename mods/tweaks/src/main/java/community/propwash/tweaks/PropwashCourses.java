package community.propwash.tweaks;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.MinecraftServer;

/** Propwash's race API, in a class of its own so servers without Propwash never load it (SecretMaps). */
final class PropwashCourses {
    private PropwashCourses() {
    }

    /** The ids (UUIDs, as text) of the courses in these dimensions. */
    static Set<String> on(MinecraftServer server, Set<String> dimensions) {
        Set<String> ids = new HashSet<>();
        for (var course : de.lardtom.propwash.api.PropwashApi.race().courses(server)) {
            if (dimensions.contains(course.dimension().toString())) {
                ids.add(course.id().toString());
            }
        }
        return ids;
    }
}
