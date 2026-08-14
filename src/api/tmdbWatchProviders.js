/**
 * Utility function to fetch watch providers from TMDB API for a given media (movie or tv)
 * specifically for the IN (India) region.
 * 
 * TMDB Endpoint: https://api.themoviedb.org/3/{media_type}/{id}/watch/providers
 * 
 * @param {('movie'|'tv')} mediaType - Media type ('movie' or 'tv')
 * @param {string|number} id - TMDB ID for the movie or TV show
 * @param {string} apiKey - TMDB API key or Bearer token
 * @returns {Promise<{ link: string|null, flatrate: Array, rent: Array, buy: Array } | null>}
 */
export async function fetchWatchProviders(mediaType, id, apiKey) {
  if (!mediaType || !id || !apiKey) {
    console.error("fetchWatchProviders error: Missing mediaType, id, or apiKey parameter.");
    return null;
  }

  // Normalize media_type to 'movie' or 'tv'
  const normalizedMediaType = mediaType.toLowerCase() === 'series' || mediaType.toLowerCase() === 'show' ? 'tv' : mediaType.toLowerCase();

  try {
    const isBearerToken = apiKey.length > 50;
    const url = `https://api.themoviedb.org/3/${normalizedMediaType}/${id}/watch/providers${isBearerToken ? '' : `?api_key=${apiKey}`}`;
    
    const headers = {
      'Content-Type': 'application/json',
    };
    if (isBearerToken) {
      headers['Authorization'] = `Bearer ${apiKey}`;
    }

    const response = await fetch(url, { headers });

    if (!response.ok) {
      throw new Error(`TMDB Watch Providers API HTTP error: ${response.status} ${response.statusText}`);
    }

    const data = await response.json();
    
    // Extract results specifically for the 'IN' (India) region
    const inRegionData = data?.results?.IN;

    if (!inRegionData) {
      return null;
    }

    return {
      link: inRegionData.link || null,
      flatrate: Array.isArray(inRegionData.flatrate) ? inRegionData.flatrate : [],
      rent: Array.isArray(inRegionData.rent) ? inRegionData.rent : [],
      buy: Array.isArray(inRegionData.buy) ? inRegionData.buy : [],
    };
  } catch (error) {
    console.error(`Error fetching watch providers for ${mediaType}/${id}:`, error);
    return null;
  }
}
